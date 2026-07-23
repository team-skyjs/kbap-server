package com.kbap.app.api.config

import com.kbap.application.foodimage.FoodImageBatchCollectService
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

// 이미지 회수 틱(KB-226): 1시간 주기, ShedLock 으로 2대 중 1대만 실행. 대부분의 틱은 no-op(SUBMITTED 0건).
// 어노테이션 AOP 대신 프로그래매틱 락 — 프록시 없이 동작이 눈에 보이고 통합 테스트가 단순하다.
@Component
class FoodImageCollectScheduler(
    private val collectService: FoodImageBatchCollectService,
    lockProvider: LockProvider,
) {
    private val lockingExecutor = DefaultLockingTaskExecutor(lockProvider)

    // initialDelay = 주기와 동일 — 부팅 직후 즉시 실행하지 않는다(틱을 놓쳐도 다음 틱이 회수, 멱등).
    @Scheduled(
        fixedDelayString = "\${kbap.food-image.collect-interval:PT1H}",
        initialDelayString = "\${kbap.food-image.collect-interval:PT1H}",
    )
    fun collect() {
        lockingExecutor.executeWithLock(
            Runnable { collectService.collectSubmitted() },
            LockConfiguration(Instant.now(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR),
        )
    }

    companion object {
        const val LOCK_NAME = "food-image-collect"

        // 최악 회수 시간(수 분)보다 넉넉하게 — 인스턴스가 죽으면 리스 만료 후 다음 틱이 재선점.
        val LOCK_AT_MOST_FOR: Duration = Duration.ofMinutes(30)

        // 시계 편차로 두 인스턴스 틱이 겹칠 때 이중 실행을 막는 최소 유지 시간.
        val LOCK_AT_LEAST_FOR: Duration = Duration.ofMinutes(1)
    }
}
