package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutbox
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class FoodVectorOutboxItemReader(
    private val outboxRepository: FoodVectorOutboxJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val pageSize: Int,
) : ItemStreamReader<FoodVectorOutbox> {
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private var cursor = 0L
    private val buffer = ArrayDeque<FoodVectorOutbox>()
    private var exhausted = false

    init {
        require(pageSize > 0) { "pageSize는 1 이상이어야 합니다" }
    }

    override fun open(executionContext: ExecutionContext) {
        cursor = 0L
        buffer.clear()
        exhausted = false
    }

    override fun read(): FoodVectorOutbox? {
        if (buffer.isEmpty() && !exhausted) {
            val page = transactionTemplate.execute {
                outboxRepository.findPendingAfterId(cursor, pageSize)
            }.orEmpty()
            if (page.isEmpty()) {
                exhausted = true
            } else {
                cursor = page.last().id
                buffer.addAll(page)
            }
        }
        return buffer.removeFirstOrNull()
    }
}
