package com.kbap.batch.food.content

import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime

class FoodContentOutboxRecovery(
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val foodRepository: FoodJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val staleAfter: Duration,
    private val maxAttempts: Int,
    private val maxPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "staleAfter 는 0 보다 커야 합니다" }
        require(maxAttempts > 0) { "maxAttempts 는 1 이상이어야 합니다" }
        require(maxPerRun > 0) { "maxPerRun 은 1 이상이어야 합니다" }
    }

    fun recoverStale(): FoodContentOutboxRecoverySummary {
        val before = LocalDateTime.now().minus(staleAfter)
        val stale = transactionTemplate.execute { outboxRepository.findStaleSent(before, maxPerRun) }.orEmpty()
        var requeued = 0
        var dead = 0
        stale.forEach { outbox ->
            transactionTemplate.executeWithoutResult {
                val food = foodRepository.findByIdForUpdate(outbox.foodId)
                if (outboxRepository.countStillStale(outbox.id, before) == 0L) return@executeWithoutResult
                if (food?.contentStatus == FoodContentStatus.READY) {
                    dead += outboxRepository.markDeadIfStillStale(
                        outbox.id,
                        before,
                        "응답 없이 ${staleAfter.toHours()}시간 초과 — 이미 공개(READY)된 음식이라 재전송하지 않는다. " +
                            "결과가 오면 검수 없이 공개 콘텐츠를 덮어쓴다. 재수집이 필요하면 어드민이 다시 요청",
                    )
                } else if (outbox.attempts >= maxAttempts) {
                    dead += outboxRepository.markDeadIfStillStale(
                        outbox.id,
                        before,
                        "응답 없이 ${staleAfter.toHours()}시간 초과 — 재시도 ${outbox.attempts}회로 상한 도달",
                    )
                } else {
                    requeued += outboxRepository.requeueIfStillStale(
                        outbox.id,
                        before,
                        "응답 없이 ${staleAfter.toHours()}시간 초과 — 재전송",
                    )
                }
            }
        }
        if (requeued > 0 || dead > 0) {
            log.warn("콘텐츠 아웃박스 회수 — 재전송 {}건, 포기 {}건", requeued, dead)
        }
        if (stale.size == maxPerRun) {
            log.warn("콘텐츠 아웃박스 회수 — 실행당 상한({})에 닿았습니다. 남은 굳은 요청은 다음 실행에서 회수합니다", maxPerRun)
        }
        return FoodContentOutboxRecoverySummary(requeued, dead)
    }
}

data class FoodContentOutboxRecoverySummary(
    val requeued: Int,
    val dead: Int,
)
