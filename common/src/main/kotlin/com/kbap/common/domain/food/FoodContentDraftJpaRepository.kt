package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodContentDraftStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface FoodContentDraftJpaRepository : JpaRepository<FoodContentDraft, Long> {
    fun findByFoodIdAndReviewStatus(foodId: Long, reviewStatus: FoodContentDraftStatus): FoodContentDraft?

    fun existsByFoodIdAndReviewStatus(foodId: Long, reviewStatus: FoodContentDraftStatus): Boolean

    fun findByReviewStatusOrderByIdAsc(reviewStatus: FoodContentDraftStatus, pageable: Pageable): Page<FoodContentDraft>
}
