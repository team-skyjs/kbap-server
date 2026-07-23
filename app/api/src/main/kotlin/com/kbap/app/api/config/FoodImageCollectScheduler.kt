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

    // cron 정시 — 두 인스턴스가 같은 순간 깨어나 ShedLock 이 1대를 고르므로 "시간당 1회"가 성립한다.
    // (인스턴스별 fixedDelay 는 부팅 시각 오프셋만큼 서로 다른 시각에 깨어나 시간당 2회 폴링이 된다.)
    @Scheduled(cron = "\${kbap.food-image.collect-cron:0 0 * * * *}")
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
