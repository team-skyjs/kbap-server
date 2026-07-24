package com.kbap.app.api.config

import com.kbap.application.foodimage.FoodImageBatchCollectService
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class FoodImageCollectScheduler(
    private val collectService: FoodImageBatchCollectService,
    lockProvider: LockProvider,
) {
    private val lockingExecutor = DefaultLockingTaskExecutor(lockProvider)

    @Scheduled(cron = "\${kbap.food-image.collect-cron:0 0 */3 * * *}")
    fun collect() {
        lockingExecutor.executeWithLock(
            Runnable { collectService.collectSubmitted() },
            LockConfiguration(Instant.now(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR),
        )
    }

    companion object {
        const val LOCK_NAME = "food-image-collect"

        val LOCK_AT_MOST_FOR: Duration = Duration.ofMinutes(30)

        val LOCK_AT_LEAST_FOR: Duration = Duration.ofMinutes(1)
    }
}
