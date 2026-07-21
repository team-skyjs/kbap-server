package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class FoodContentBatchService internal constructor(
    private val foodRepository: FoodJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getIncompleteFoods(afterId: Long?, size: Int): List<Food> =
        foodRepository.findIncompleteAfter(afterId, PageRequest.of(0, size))

    // REQUIRES_NEW — 뒤 작업이 실패해도 이 작업 결과는 커밋 유지(재실행 시 실패 작업만 재시도).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveProgress(food: Food) {
        foodRepository.save(food)
    }

    @Transactional
    fun completeContent(food: Food, hasAvoidanceMapping: Boolean): Boolean {
        val transitioned = food.transitionToReadyIfComplete(hasAvoidanceMapping)
        foodRepository.save(food)
        return transitioned
    }
}
