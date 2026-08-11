package com.kbap.batch.outbox

import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.port.mq.FoodContentEvent
import com.kbap.common.port.mq.FoodContentEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class FoodContentOutboxPublisher(
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val eventPublisher: FoodContentEventPublisher,
    transactionManager: PlatformTransactionManager,
    private val pageSize: Int,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    init {
        require(pageSize > 0) { "pageSize는 1 이상이어야 합니다" }
    }

    fun publishAll(): FoodContentOutboxPublishSummary {
        var cursor = 0L
        var attempted = 0
        var succeeded = 0
        var failed = 0

        while (true) {
            val outboxes = transactionTemplate.execute {
                outboxRepository.findPendingAfterId(cursor, pageSize)
            }.orEmpty()
            if (outboxes.isEmpty()) {
                break
            }

            cursor = outboxes.last().id
            val result = eventPublisher.publish(
                outboxes.map {
                    FoodContentEvent(
                        outboxId = it.id,
                        foodId = it.foodId,
                        scannedName = it.displayName,
                    )
                },
            )
            transactionTemplate.executeWithoutResult {
                if (result.succeededOutboxIds.isNotEmpty()) {
                    outboxRepository.recordPublishSucceeded(result.succeededOutboxIds)
                }
                if (result.failedOutboxIds.isNotEmpty()) {
                    outboxRepository.recordPublishFailed(result.failedOutboxIds)
                }
            }

            attempted += outboxes.size
            succeeded += result.succeededOutboxIds.size
            failed += result.failedOutboxIds.size
        }

        return FoodContentOutboxPublishSummary(attempted, succeeded, failed)
    }
}
