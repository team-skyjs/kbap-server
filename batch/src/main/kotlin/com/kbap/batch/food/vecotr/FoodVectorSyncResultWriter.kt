package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutbox
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

class FoodVectorSyncResultWriter(
    private val outboxRepository: FoodVectorOutboxJpaRepository,
) : ItemWriter<FoodVectorOutbox> {
    override fun write(chunk: Chunk<out FoodVectorOutbox>) {
        chunk.forEach { item ->
            outboxRepository.findById(item.id).orElse(null)?.complete()
        }
    }
}
