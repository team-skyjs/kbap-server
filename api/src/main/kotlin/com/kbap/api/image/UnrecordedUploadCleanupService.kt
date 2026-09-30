package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class UnrecordedUploadCleanupService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val storageObjectStore: StorageObjectStore,
    private val redisTemplate: StringRedisTemplate,
    uploadProperties: ImageUploadProperties,
    @Value("\${kbap.uploaded-image-cleanup.dry-run:true}") private val dryRun: Boolean,
    @Value("\${kbap.uploaded-image-cleanup.unrecorded-retention-days:7}") private val retentionDays: Long,
    @Value("\${kbap.uploaded-image-cleanup.unrecorded-max-deletes-per-run:100}") private val maxDeletesPerRun: Int,
    @Value("\${kbap.uploaded-image-cleanup.unrecorded-max-listed-per-run:20000}") private val maxListedPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val retention: Duration = Duration.ofDays(retentionDays)
    private val prefixes: Map<UploadPurpose, String> = uploadPrefixes(uploadProperties.keyPrefix)
    private val purposes: List<UploadPurpose> = prefixes.keys.toList()

    init {
        val completeDeadline = uploadProperties.uploadTtl.plus(uploadProperties.completeWindow)
        check(retention > completeDeadline) {
            "행 없는 오브젝트 보존 기간(${retention})은 presigned 유효기간(${uploadProperties.uploadTtl}) + " +
                "완료 신고 허용 창(${uploadProperties.completeWindow}) 보다 길어야 한다"
        }
    }

    @Scheduled(cron = "\${kbap.uploaded-image-cleanup.unrecorded-cron:0 50 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "unrecorded-upload-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun cleanup(): UnrecordedUploadCleanupResult {
        val cutoff = Instant.now().minus(retention)
        val start = loadCursor()
        var stuck = start.stuck
        var listed = 0
        var processed = 0
        var deleted = 0
        var failed = 0
        var skipped = 0
        var skippedStuck = 0
        val counts = linkedMapOf<String, UnrecordedUploadCount>()
        var purposeIndex = purposes.indexOf(start.purpose).coerceAtLeast(0)
        var settledKey: String? = start.afterPath
        var cursor = Cursor(purposes.getOrNull(purposeIndex), settledKey, stuck)
        var stopReason: String? = null
        var completedRound = false
        while (purposeIndex < purposes.size) {
            val purpose = purposes[purposeIndex]
            var purposeListed = 0
            var unrecorded = 0
            var stale = 0
            var exhausted = false
            while (listed < maxListedPerRun) {
                val page = storageObjectStore.list(prefixes.getValue(purpose), settledKey, minOf(LIST_PAGE_SIZE, maxListedPerRun - listed))
                if (page.isEmpty()) {
                    exhausted = true
                    break
                }
                listed += page.size
                purposeListed += page.size
                val recorded = uploadedImageRepository.findRecordedPathsAnyStatus(page.map { it.path }).toSet()
                for (obj in page) {
                    val isUnrecorded = obj.path !in recorded
                    if (isUnrecorded) unrecorded++
                    if (isUnrecorded && obj.lastModified.isBefore(cutoff)) {
                        if (processed >= maxDeletesPerRun) {
                            stopReason = "삭제 상한 $maxDeletesPerRun"
                            break
                        }
                        processed++
                        stale++
                        if (!dryRun) {
                            val current = stuck
                            if (current != null && current.key == obj.path && current.failures >= MAX_CONSECUTIVE_FAILURES) {
                                log.error("행 없는 업로드 오브젝트 삭제가 {}번 연속 실패해 건너뛴다 — 수동 확인 필요 path={}", current.failures, obj.path)
                                skippedStuck++
                                stuck = null
                            } else {
                                val outcome = deleteIfStillUnrecorded(obj)
                                if (outcome == Outcome.FAILED) {
                                    failed++
                                    stuck = Stuck(obj.path, if (current?.key == obj.path) current.failures + 1 else 1)
                                    stopReason = "삭제 실패 ${obj.path}"
                                    break
                                }
                                if (outcome == Outcome.DELETED) deleted++ else skipped++
                                if (current?.key == obj.path) stuck = null
                            }
                        }
                    }
                    settledKey = obj.path
                }
                cursor = Cursor(purpose, settledKey, stuck)
                if (stopReason != null) break
            }
            counts[purpose.prefix] = UnrecordedUploadCount(listed = purposeListed, unrecorded = unrecorded, stale = stale)
            if (stopReason != null) break
            if (!exhausted) {
                stopReason = "목록 상한 $maxListedPerRun"
                cursor = Cursor(purpose, settledKey, stuck)
                break
            }
            purposeIndex++
            settledKey = null
            cursor = if (purposeIndex < purposes.size) Cursor(purposes[purposeIndex], null, stuck) else Cursor(null, null, null).also { completedRound = true }
        }
        saveCursor(cursor)
        val result = UnrecordedUploadCleanupResult(dryRun, counts, deleted, failed, skipped, skippedStuck)
        if (dryRun) {
            log.info("행 없는 업로드 오브젝트 정리 dry-run — 용도별 {목록, 행 없음, 보존 기간 경과(이번 실행 범위)} {}", counts)
        } else if (failed > 0) {
            log.warn("행 없는 업로드 오브젝트 정리 — {}건 삭제, {}건 실패, {}건 건너뜀(그 사이 기록됨) {}", deleted, failed, skipped, counts)
        } else {
            log.info("행 없는 업로드 오브젝트 정리 — {}건 삭제, {}건 건너뜀(그 사이 기록됨) {}", deleted, skipped, counts)
        }
        if (completedRound) {
            log.info("행 없는 업로드 오브젝트 정리 — 전 용도를 한 바퀴 돌았다. 다음 실행은 처음부터 본다")
        } else {
            log.info("행 없는 업로드 오브젝트 정리 — {} 에서 멈췄다. 커서 {} 를 저장했고 다음 실행이 그 뒤부터 이어 본다", stopReason, cursor)
        }
        return result
    }

    private fun loadCursor(): Cursor =
        try {
            redisTemplate.opsForValue().get(CURSOR_KEY)?.let { Cursor.parse(it) } ?: Cursor(null, null, null)
        } catch (e: RuntimeException) {
            log.warn("정리 커서(Redis)를 읽지 못해 처음부터 본다", e)
            Cursor(null, null, null)
        }

    private fun saveCursor(cursor: Cursor) {
        try {
            if (cursor.purpose == null) redisTemplate.delete(CURSOR_KEY) else redisTemplate.opsForValue().set(CURSOR_KEY, cursor.serialize())
        } catch (e: RuntimeException) {
            log.warn("정리 커서(Redis)를 저장하지 못했다 — 다음 실행은 처음부터 본다", e)
        }
    }

    private data class Stuck(val key: String, val failures: Int)

    private data class Cursor(val purpose: UploadPurpose?, val afterPath: String?, val stuck: Stuck?) {
        fun serialize(): String =
            listOf(purpose?.prefix.orEmpty(), afterPath.orEmpty(), stuck?.key.orEmpty(), stuck?.failures?.toString().orEmpty())
                .joinToString("|").trimEnd('|')

        override fun toString(): String = serialize()

        companion object {
            fun parse(raw: String): Cursor {
                val parts = raw.split('|')
                val purpose = UploadPurpose.entries.firstOrNull { it.prefix == parts[0] } ?: return Cursor(null, null, null)
                val afterPath = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                val stuck = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Stuck(it, parts.getOrNull(3)?.toIntOrNull() ?: 1) }
                return Cursor(purpose, afterPath, stuck)
            }
        }
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
        const val CURSOR_KEY = "upload-cleanup:cursor"
        const val MAX_CONSECUTIVE_FAILURES = 3
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
    val skippedStuckCount: Int,
)
