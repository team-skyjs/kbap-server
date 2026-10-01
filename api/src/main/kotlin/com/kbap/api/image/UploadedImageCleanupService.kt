package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.port.storage.StorageObjectStore
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class UploadedImageCleanupService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val storageObjectStore: StorageObjectStore,
    @Value("\${kbap.uploaded-image-cleanup.retention-days:7}") private val retentionDays: Long,
    @Value("\${kbap.uploaded-image-cleanup.dry-run:true}") private val dryRun: Boolean,
    @Value("\${kbap.uploaded-image-cleanup.page-size:100}") private val pageSize: Int,
    @Value("\${kbap.uploaded-image-cleanup.max-per-run:100}") private val maxPerRun: Int,
    transactionManager: PlatformTransactionManager,
    private val metrics: UploadCleanupMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)
    private val countTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Volatile
    private var latestOrphanCounts: OrphanUploadCounts? = null

    @Scheduled(cron = "\${kbap.uploaded-image-cleanup.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "uploaded-image-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun cleanup(): UploadedImageCleanupResult {
        val before = orphanCutoff()
        if (dryRun) {
            val counts = refreshOrphanCounts()
            log.info("미참조 업로드 정리 dry-run — 삭제 대상 {}", counts?.counts)
            return recordRun(UploadedImageCleanupResult(dryRun = true, deletedCount = 0, failedCount = 0))
        }
        var deleted = 0
        var failed = 0
        var afterId = 0L
        var attempted = 0
        while (attempted < maxPerRun) {
            val ids = uploadedImageRepository.findOrphanIds(before, afterId, minOf(pageSize, maxPerRun - attempted))
            if (ids.isEmpty()) break
            attempted += ids.size
            ids.forEach { id ->
                when (deleteOne(id, before)) {
                    DeleteOutcome.DELETED -> deleted++
                    DeleteOutcome.FAILED -> failed++
                    DeleteOutcome.SKIPPED -> Unit
                }
            }
            afterId = ids.last()
        }
        if (failed > 0) {
            log.warn("미참조 업로드 정리 — {}건 삭제, {}건 실패(행은 ACTIVE 로 남아 다음 실행에서 다시 시도한다)", deleted, failed)
        } else {
            log.info("미참조 업로드 정리 — {}건 삭제", deleted)
        }
        if (attempted >= maxPerRun) {
            log.info("미참조 업로드 정리 — 실행당 상한 {}건에 닿았다. 남은 대상은 다음 실행에서 이어 간다", maxPerRun)
        }
        refreshOrphanCounts()
        return recordRun(UploadedImageCleanupResult(dryRun = false, deletedCount = deleted, failedCount = failed))
    }

    private fun recordRun(result: UploadedImageCleanupResult): UploadedImageCleanupResult {
        metrics.record(UploadCleanupMetrics.RECORDED, UploadCleanupMetrics.ALL_PURPOSES, "deleted", result.deletedCount.toLong())
        metrics.record(UploadCleanupMetrics.RECORDED, UploadCleanupMetrics.ALL_PURPOSES, "failed", result.failedCount.toLong())
        metrics.markRun(UploadCleanupMetrics.RECORDED)
        return result
    }

    @Async
    @EventListener(ApplicationReadyEvent::class)
    fun refreshOrphanCountsOnStartup() {
        refreshOrphanCounts()
    }

    @Scheduled(cron = "\${kbap.uploaded-image-cleanup.count-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    fun refreshOrphanCounts(): OrphanUploadCounts? {
        val before = orphanCutoff()
        latestOrphanCounts = runCatching { OrphanUploadCounts(countTransaction.execute { countOrphansIn(before) }!!, LocalDateTime.now()) }
            .onFailure { log.warn("미참조 업로드 건수를 세지 못했다 — 최근값을 비운다", it) }
            .getOrNull()
        latestOrphanCounts?.counts?.forEach { (purpose, count) ->
            metrics.record(UploadCleanupMetrics.RECORDED, purpose, "candidate", count)
        }
        return latestOrphanCounts
    }

    fun getLatestOrphanCounts(): OrphanUploadCounts? = latestOrphanCounts

    protected fun countOrphansIn(before: LocalDateTime): Map<String, Long> =
        UploadedImageJpaRepository.CLEANUP_SEGMENTS.associate { segment ->
            segment.removePrefix("images/").trimEnd('/') to uploadedImageRepository.countOrphansIn(before, segment)
        }

    private fun orphanCutoff(): LocalDateTime = LocalDateTime.now().minusDays(retentionDays)

    private fun deleteOne(id: Long, before: LocalDateTime): DeleteOutcome =
        try {
            transaction.execute {
                val upload = uploadedImageRepository.findByIdForUpdate(id) ?: return@execute DeleteOutcome.SKIPPED
                if (uploadedImageRepository.countOrphan(id, before) == 0L) return@execute DeleteOutcome.SKIPPED
                storageObjectStore.delete(upload.path)
                upload.delete()
                DeleteOutcome.DELETED
            }!!
        } catch (e: RuntimeException) {
            log.warn("미참조 업로드 삭제 실패 — 행은 ACTIVE 로 남는다 id={}", id, e)
            DeleteOutcome.FAILED
        }

    private enum class DeleteOutcome { DELETED, SKIPPED, FAILED }
}

data class UploadedImageCleanupResult(
    val dryRun: Boolean,
    val deletedCount: Int,
    val failedCount: Int,
)

data class OrphanUploadCounts(
    val counts: Map<String, Long>,
    val computedAt: LocalDateTime,
)
