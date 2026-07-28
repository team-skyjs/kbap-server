package com.kbap.app.batch.content

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

class FoodContentItemWriter(
    private val foodRepository: FoodJpaRepository,
) : ItemWriter<Food> {

    override fun write(chunk: Chunk<out Food>) {
        val byTarget = chunk.items.groupBy { it.transitionByContentState() }
        byTarget[FoodContentStatus.PENDING_IMAGE]
            ?.let { foodRepository.markPendingImageByIdIn(it.map(Food::id)) }
        byTarget[FoodContentStatus.PENDING_REVIEW]
            ?.let { foodRepository.markPendingReviewByIdIn(it.map(Food::id)) }
    }
}
