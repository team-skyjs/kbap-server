package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.port.storage.StorageObjectStore
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class UploadedImageCleanupService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val storageObjectStore: StorageObjectStore,
    @Value("\${kbap.uploaded-image-cleanup.retention-days:7}") private val retentionDays: Long,
    @Value("\${kbap.uploaded-image-cleanup.dry-run:true}") private val dryRun: Boolean,
    @Value("\${kbap.uploaded-image-cleanup.page-size:100}") private val pageSize: Int,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)
    private val countTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }

    @Scheduled(cron = "\${kbap.uploaded-image-cleanup.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "uploaded-image-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun cleanup(): UploadedImageCleanupResult {
        val before = orphanCutoff()
        if (dryRun) {
            val counts = countOrphans(before)
            log.info("미참조 업로드 정리 dry-run — 삭제 대상 {}", counts)
            return UploadedImageCleanupResult(dryRun = true, deletedCount = 0, orphanCounts = counts)
        }
        var deleted = 0
        var afterId = 0L
        while (true) {
            val ids = uploadedImageRepository.findOrphanIds(before, afterId, pageSize)
            if (ids.isEmpty()) break
            ids.forEach { id -> if (deleteOne(id, before)) deleted++ }
            afterId = ids.last()
        }
        log.info("미참조 업로드 정리 — {}건 삭제", deleted)
        return UploadedImageCleanupResult(dryRun = false, deletedCount = deleted, orphanCounts = countOrphans(before))
    }

    fun countOrphans(before: LocalDateTime = orphanCutoff()): Map<String, Long> =
        countTransaction.execute { countOrphansIn(before) }!!

    protected fun countOrphansIn(before: LocalDateTime): Map<String, Long> =
        UploadedImageJpaRepository.CLEANUP_SEGMENTS.associate { segment ->
            segment.removePrefix("images/").trimEnd('/') to uploadedImageRepository.countOrphansIn(before, segment)
        }

    private fun orphanCutoff(): LocalDateTime = LocalDateTime.now().minusDays(retentionDays)

    private fun deleteOne(id: Long, before: LocalDateTime): Boolean {
        val path = transaction.execute {
            val path = uploadedImageRepository.findById(id).orElse(null)?.path
            if (path != null && uploadedImageRepository.deleteIfOrphan(id, before) == 1) path else null
        } ?: return false
        runCatching { storageObjectStore.delete(path) }
            .onFailure { log.warn("미참조 업로드 S3 삭제 실패 — 행은 DELETED, 오브젝트는 남는다 id={} path={}", id, path, it) }
        return true
    }
}

data class UploadedImageCleanupResult(
    val dryRun: Boolean,
    val deletedCount: Int,
    val orphanCounts: Map<String, Long>,
)
