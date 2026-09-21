package com.kbap.batch.food.vector

import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutbox
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

class FoodVectorOutboxItemReader(
    private val outboxRepository: FoodVectorOutboxJpaRepository,
    private val pageSize: Int,
) : ItemStreamReader<FoodVectorOutbox> {
    private var cursor = 0L
    private val candidates = ArrayDeque<FoodVectorOutbox>()
    private var exhausted = false

    init {
        require(pageSize > 0) { "pageSize는 1 이상이어야 합니다" }
    }

    override fun open(executionContext: ExecutionContext) {
        cursor = 0L
        candidates.clear()
        exhausted = false
    }

    override fun read(): FoodVectorOutbox? {
        if (candidates.isEmpty() && !exhausted) {
            val page = outboxRepository.findPendingAfterId(cursor, pageSize)
            if (page.isEmpty()) {
                exhausted = true
            } else {
                cursor = page.last().id
                candidates.addAll(page)
            }
        }
        return candidates.removeFirstOrNull()
    }
}
