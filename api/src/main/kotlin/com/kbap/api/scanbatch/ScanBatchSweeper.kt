package com.kbap.api.scanbatch

import com.kbap.common.domain.scan.ScanOperationPhotoJpaRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class ScanBatchSweeper(
    private val photos: ScanOperationPhotoJpaRepository,
    private val runner: ScanBatchRunner,
    private val batchSwitch: ScanBatchSwitch,
) {
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    @SchedulerLock(name = "scanBatchSweep", lockAtMostFor = "PT25S")
    fun tick() = sweep()

    fun sweep() {
        if (!batchSwitch.enabled || !batchSwitch.executionEnabled) return
        val now = LocalDateTime.now()
        photos.findStalePending(ScanBatchService.NAMESPACE, now, now.minus(STALE_AFTER), PageRequest.of(0, MAX_PHOTOS_PER_SWEEP))
            .groupBy { it.memberId to ScanBatchService.uuidOf(it.requestKey) }
            .forEach { (key, rows) -> runner.dispatch(key.first, key.second, rows.map { it.selectionIndex }) }
    }

    companion object {
        private val STALE_AFTER: Duration = Duration.ofSeconds(30)
        private const val MAX_PHOTOS_PER_SWEEP = 500
    }
}
