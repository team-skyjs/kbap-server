package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicInteger

class FoodVectorSyncResultWriter(
    private val outboxRepository: FoodVectorOutboxJpaRepository,
    transactionManager: PlatformTransactionManager,
) : ItemWriter<FoodVectorSyncOutcome> {
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val completed = AtomicInteger()
    private val failed = AtomicInteger()

    override fun write(chunk: Chunk<out FoodVectorSyncOutcome>) {
        transactionTemplate.executeWithoutResult {
            chunk.forEach { outcome ->
                val outbox = outboxRepository.findById(outcome.outboxId).orElse(null) ?: return@forEach
                val error = outcome.error
                if (error == null) outbox.complete() else outbox.recordFailure(error)
            }
        }
        chunk.forEach { if (it.error == null) completed.incrementAndGet() else failed.incrementAndGet() }
    }

    fun summary(): FoodVectorSyncSummary =
        FoodVectorSyncSummary(
            attempted = completed.get() + failed.get(),
            completed = completed.get(),
            failed = failed.get(),
        )
}

data class FoodVectorSyncOutcome(
    val outboxId: Long,
    val error: String?,
) {
    companion object {
        fun success(outboxId: Long): FoodVectorSyncOutcome = FoodVectorSyncOutcome(outboxId, null)

        fun failure(outboxId: Long, error: String): FoodVectorSyncOutcome = FoodVectorSyncOutcome(outboxId, error)
    }
}

data class FoodVectorSyncSummary(
    val attempted: Int,
    val completed: Int,
    val failed: Int,
)
