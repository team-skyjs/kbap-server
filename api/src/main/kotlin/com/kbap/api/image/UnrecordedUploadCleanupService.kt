package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class UnrecordedUploadCleanupService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val storageObjectStore: StorageObjectStore,
    uploadProperties: ImageUploadProperties,
    @Value("\${kbap.uploaded-image-cleanup.dry-run:true}") private val dryRun: Boolean,
    @Value("\${kbap.uploaded-image-cleanup.unrecorded-retention-days:7}") private val retentionDays: Long,
    @Value("\${kbap.uploaded-image-cleanup.unrecorded-max-deletes-per-run:100}") private val maxDeletesPerRun: Int,
    @Value("\${kbap.uploaded-image-cleanup.unrecorded-max-listed-per-run:20000}") private val maxListedPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val retention: Duration = Duration.ofDays(retentionDays)
    private val prefixes: Map<UploadPurpose, String> = uploadPrefixes(uploadProperties.keyPrefix)

    init {
        val completeDeadline = uploadProperties.uploadTtl.plus(COMPLETE_GRACE)
        check(retention > completeDeadline) {
            "행 없는 오브젝트 보존 기간(${retention})은 presigned 유효기간(${uploadProperties.uploadTtl}) + 완료 신고 여유(${COMPLETE_GRACE}) 보다 길어야 한다"
        }
    }

    @Scheduled(cron = "\${kbap.uploaded-image-cleanup.unrecorded-cron:0 50 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "unrecorded-upload-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun cleanup(): UnrecordedUploadCleanupResult {
        val cutoff = Instant.now().minus(retention)
        var listed = 0
        var deleted = 0
        var failed = 0
        var skipped = 0
        val counts = linkedMapOf<String, UnrecordedUploadCount>()
        for ((purpose, prefix) in prefixes) {
            var purposeListed = 0
            var unrecorded = 0
            var stale = 0
            var afterPath: String? = null
            while (listed < maxListedPerRun) {
                val page = storageObjectStore.list(prefix, afterPath, minOf(LIST_PAGE_SIZE, maxListedPerRun - listed))
                if (page.isEmpty()) break
                listed += page.size
                purposeListed += page.size
                val recorded = uploadedImageRepository.findRecordedPathsAnyStatus(page.map { it.path }).toSet()
                val unrecordedObjects = page.filter { it.path !in recorded }
                unrecorded += unrecordedObjects.size
                val staleObjects = unrecordedObjects.filter { it.lastModified.isBefore(cutoff) }
                stale += staleObjects.size
                if (!dryRun) {
                    for (candidate in staleObjects) {
                        if (deleted + failed >= maxDeletesPerRun) break
                        when (deleteIfStillUnrecorded(candidate)) {
                            Outcome.DELETED -> deleted++
                            Outcome.FAILED -> failed++
                            Outcome.SKIPPED -> skipped++
                        }
                    }
                }
                afterPath = page.last().path
            }
            counts[purpose.prefix] = UnrecordedUploadCount(listed = purposeListed, unrecorded = unrecorded, stale = stale)
        }
        val result = UnrecordedUploadCleanupResult(dryRun, counts, deleted, failed, skipped)
        if (dryRun) {
            log.info("행 없는 업로드 오브젝트 정리 dry-run — 용도별 {목록, 행 없음, 보존 기간 경과} {}", counts)
        } else if (failed > 0) {
            log.warn("행 없는 업로드 오브젝트 정리 — {}건 삭제, {}건 실패, {}건 건너뜀(그 사이 기록됨) {}", deleted, failed, skipped, counts)
        } else {
            log.info("행 없는 업로드 오브젝트 정리 — {}건 삭제, {}건 건너뜀(그 사이 기록됨) {}", deleted, skipped, counts)
        }
        if (deleted + failed >= maxDeletesPerRun || listed >= maxListedPerRun) {
            log.info("행 없는 업로드 오브젝트 정리 — 실행당 상한(삭제 {}·목록 {})에 닿았다. 남은 대상은 다음 실행에서 이어 간다", maxDeletesPerRun, maxListedPerRun)
        }
        return result
    }

    private fun deleteIfStillUnrecorded(candidate: StoredObject): Outcome {
        if (uploadedImageRepository.countRecordedAnyStatus(candidate.path) > 0) return Outcome.SKIPPED
        return try {
            storageObjectStore.delete(candidate.path)
            Outcome.DELETED
        } catch (e: RuntimeException) {
            log.warn("행 없는 업로드 오브젝트 삭제 실패 — 다음 실행에서 다시 시도한다 path={}", candidate.path, e)
            Outcome.FAILED
        }
    }

    private enum class Outcome { DELETED, FAILED, SKIPPED }

    companion object {
        val COMPLETE_GRACE: Duration = Duration.ofDays(1)
        private const val LIST_PAGE_SIZE = 1000

        fun uploadPrefixes(keyPrefix: String): Map<UploadPurpose, String> {
            val prefix = keyPrefix.trim('/')
            return UploadPurpose.entries.associateWith { purpose ->
                (if (prefix.isEmpty()) "" else "$prefix/") + "images/${purpose.prefix}/"
            }
        }
    }
}

data class UnrecordedUploadCount(
    val listed: Int,
    val unrecorded: Int,
    val stale: Int,
)

data class UnrecordedUploadCleanupResult(
    val dryRun: Boolean,
    val counts: Map<String, UnrecordedUploadCount>,
    val deletedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
)
