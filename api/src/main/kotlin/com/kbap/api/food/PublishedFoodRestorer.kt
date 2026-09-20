package com.kbap.api.food

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PublishedFoodRestorer(
    private val foodRepository: FoodJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun restore(foodIds: Collection<Long>) = foodIds.forEach(::restoreOne)

    private fun restoreOne(foodId: Long) {
        val food = foodRepository.findById(foodId).orElse(null) ?: return
        if (food.contentStatus != FoodContentStatus.PENDING_IMAGE || food.publishedAt == null) return
        food.contentStatus = FoodContentStatus.READY
        foodRepository.save(food)
        vectorOutboxRepository
            .findByFoodIdAndOperationAndOutboxStatus(foodId, FoodVectorOutboxOperation.DELETE, FoodVectorOutboxStatus.PENDING)
            .forEach { it.delete() }
        vectorOutboxRepository.enqueueIfAbsent(foodId, FoodVectorOutboxOperation.UPSERT)
        log.warn("이미지 생성 실패로 공개 상태를 되돌렸다 — foodId={}", foodId)
    }
}
