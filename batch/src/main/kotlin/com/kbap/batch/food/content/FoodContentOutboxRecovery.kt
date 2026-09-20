package com.kbap.batch.food.content

import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime

class FoodContentOutboxRecovery(
    private val outboxRepository: FoodContentOutboxJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val staleAfter: Duration,
    private val maxAttempts: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "staleAfter 는 0 보다 커야 합니다" }
        require(maxAttempts > 0) { "maxAttempts 는 1 이상이어야 합니다" }
    }

    fun recoverStale(): FoodContentOutboxRecoverySummary {
        var requeued = 0
        var dead = 0
        while (true) {
            val stale = transactionTemplate.execute {
                outboxRepository.findStaleSent(LocalDateTime.now().minus(staleAfter), PAGE_SIZE)
            }.orEmpty()
            if (stale.isEmpty()) break
            transactionTemplate.executeWithoutResult {
                stale.forEach { outbox ->
                    if (outbox.attempts >= maxAttempts) {
                        dead += outboxRepository.markDeadIfStillSent(
                            outbox.id,
                            "응답 없이 ${staleAfter.toHours()}시간 초과 — 재시도 ${outbox.attempts}회로 상한 도달",
                        )
                    } else {
                        requeued += outboxRepository.requeueIfStillSent(
                            outbox.id,
                            "응답 없이 ${staleAfter.toHours()}시간 초과 — 재전송",
                        )
                    }
                }
            }
        }
        if (requeued > 0 || dead > 0) {
            log.warn("콘텐츠 아웃박스 회수 — 재전송 {}건, 포기 {}건", requeued, dead)
        }
        return FoodContentOutboxRecoverySummary(requeued, dead)
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

data class FoodContentOutboxRecoverySummary(
    val requeued: Int,
    val dead: Int,
)
