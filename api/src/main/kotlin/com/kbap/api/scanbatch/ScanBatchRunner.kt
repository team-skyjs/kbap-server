package com.kbap.api.scanbatch

import com.kbap.api.core.config.ScanBatchExecutorConfig
import com.kbap.common.domain.scan.model.ScanOperationPhoto
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.llm.MenuBoardLineExtractor
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import org.springframework.scheduling.TaskScheduler
import java.util.UUID

@Component
class ScanBatchRunner(
    private val batches: ScanBatchService,
    private val batchSwitch: ScanBatchSwitch,
    private val extractor: MenuBoardLineExtractor,
    @Qualifier(ScanBatchExecutorConfig.SCAN_BATCH_EXECUTOR) private val executor: TaskExecutor,
    private val scheduler: TaskScheduler,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val works = HashMap<WorkKey, BatchWork>()

    fun dispatch(memberId: Long, batchId: UUID, selectionIndexes: List<Int>) {
        if (batchSwitch.executionEnabled) enqueue(memberId, batchId, selectionIndexes)
    }

    private fun enqueue(memberId: Long, batchId: UUID, jobs: List<Int>) {
        val key = WorkKey(memberId, batchId)
        val work = synchronized(works) {
            works.getOrPut(key) { BatchWork() }.also { work -> jobs.filterNot { it in work.queue || it in work.inFlight }.forEach(work.queue::addLast) }
        }
        spawn(key, work)
    }

    private fun spawn(key: WorkKey, work: BatchWork) {
        while (true) {
            synchronized(works) {
                if (work.active >= PER_BATCH_CONCURRENCY || work.queue.isEmpty()) return
                work.active += 1
            }
            try {
                executor.execute { drain(key, work) }
            } catch (e: TaskRejectedException) {
                synchronized(works) { work.active -= 1 }
                log.warn("스캔 묶음 실행기 포화 — 잠시 뒤 다시 띄운다, batchId={}", key.batchId, e)
                scheduler.schedule({ spawn(key, work) }, Instant.now().plus(SLOT_WAIT))
                return
            }
        }
    }

    private fun drain(key: WorkKey, work: BatchWork) {
        while (true) {
            val job = synchronized(works) {
                work.queue.removeFirstOrNull()?.also(work.inFlight::add) ?: run {
                    work.active -= 1
                    if (work.active == 0) works.remove(key, work)
                    null
                }
            } ?: return
            val done = run(key.memberId, key.batchId, job)
            synchronized(works) {
                work.inFlight.remove(job)
                if (!done) {
                    work.queue.addFirst(job)
                    work.active -= 1
                }
            }
            if (!done) {
                scheduler.schedule({ spawn(key, work) }, Instant.now().plus(SLOT_WAIT))
                return
            }
        }
    }

    private fun run(memberId: Long, batchId: UUID, selectionIndex: Int): Boolean {
        val claim = try {
            batches.startAttempt(memberId, batchId, selectionIndex)
        } catch (e: ScanBatchSlotsBusyException) {
            return false
        } catch (e: BusinessException) {
            val reason = TERMINAL_REASONS[e.errorCode]
            if (reason == null) {
                log.warn("스캔 묶음 사진 시도 시작 실패 — 잠시 뒤 다시 시도 batchId={} index={}", batchId, selectionIndex, e)
                return false
            }
            runCatching { batches.failBeforeAttempt(memberId, batchId, selectionIndex, reason) }
                .onFailure { log.warn("스캔 묶음 사진 종결 기록 실패 batchId={} index={}", batchId, selectionIndex, it) }
            return true
        } catch (e: Exception) {
            log.warn("스캔 묶음 사진 시도 시작 실패 — 잠시 뒤 다시 시도 batchId={} index={}", batchId, selectionIndex, e)
            return false
        } ?: return true
        extractAndRecord(claim)
        return true
    }

    private fun extractAndRecord(claim: ScanBatchClaim) {
        val lines = try {
            extractor.extractLines(claim.objectKey)
        } catch (e: Exception) {
            val reason = when (e) {
                is MenuBoardVisionRateLimitedException -> RATE_LIMITED
                is MenuBoardVisionQuotaExhaustedException, is MenuBoardVisionUnavailableException -> VISION_UNAVAILABLE
                else -> RECOGNITION_FAILED
            }
            log.warn("스캔 묶음 사진 인식 실패 operationId={} photoRowId={} reason={}", claim.operationId, claim.photoRowId, reason, e)
            record { batches.recordFailure(claim, reason) }
            return
        }
        record { if (lines.isEmpty()) batches.recordFailure(claim, ScanOperationPhoto.NOT_DETECTED) else batches.recordSuccess(claim, lines) }
    }

    private fun record(block: () -> Boolean) {
        runCatching(block).onFailure { log.error("스캔 묶음 결과 확정 실패 — lease 만료로 재시도 가능 상태가 된다", it) }
    }

    private data class WorkKey(val memberId: Long, val batchId: UUID)

    private class BatchWork {
        val queue = ArrayDeque<Int>()
        val inFlight = mutableSetOf<Int>()
        var active = 0
    }

    companion object {
        const val PER_BATCH_CONCURRENCY = 3
        private val SLOT_WAIT: Duration = Duration.ofMillis(500)
        private val TERMINAL_REASONS = mapOf(
            ErrorCode.SCAN_BATCH_ATTEMPT_LIMIT to ScanOperationPhoto.ATTEMPT_LIMIT,
            ErrorCode.SCAN_LIMIT_EXCEEDED to ScanOperationPhoto.QUOTA_EXHAUSTED,
        )
        const val RATE_LIMITED = "RATE_LIMITED"
        const val VISION_UNAVAILABLE = "VISION_UNAVAILABLE"
        const val RECOGNITION_FAILED = "RECOGNITION_FAILED"
    }
}
