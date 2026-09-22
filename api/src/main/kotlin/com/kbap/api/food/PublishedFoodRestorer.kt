package com.kbap.api.food

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.model.ImageBatchItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PublishedFoodRestorer(
    private val foodRepository: FoodJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun restoreFailed(items: Collection<ImageBatchItem>) =
        items.filter { it.restoresPublicationOnFailure() }.forEach { restore(it.foodId) }

    private fun restore(foodId: Long) {
        val food = foodRepository.findById(foodId).orElse(null) ?: return
        if (food.contentStatus != FoodContentStatus.PENDING_IMAGE) return
        food.contentStatus = FoodContentStatus.READY
        foodRepository.save(food)
        vectorOutboxRepository
            .findByFoodIdAndOperationAndOutboxStatus(foodId, FoodVectorOutboxOperation.DELETE, FoodVectorOutboxStatus.PENDING)
            .forEach { it.delete() }
        vectorOutboxRepository.enqueueIfAbsent(foodId, FoodVectorOutboxOperation.UPSERT)
        log.warn("교체 재생성이 실패해 옛 이미지로 공개를 되돌렸다 — foodId={}", foodId)
    }
}
