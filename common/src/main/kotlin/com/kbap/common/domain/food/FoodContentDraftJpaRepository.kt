package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodContentDraftStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodContentDraftJpaRepository : JpaRepository<FoodContentDraft, Long> {
    fun findByFoodIdAndReviewStatus(foodId: Long, reviewStatus: FoodContentDraftStatus): FoodContentDraft?

    fun existsByFoodIdAndReviewStatus(foodId: Long, reviewStatus: FoodContentDraftStatus): Boolean

    @Query(
        value = "select d from FoodContentDraft d where d.reviewStatus = :reviewStatus " +
            "and exists (select f.id from Food f where f.id = d.foodId) order by d.id asc",
        countQuery = "select count(d) from FoodContentDraft d where d.reviewStatus = :reviewStatus " +
            "and exists (select f.id from Food f where f.id = d.foodId)",
    )
    fun findOfActiveFoods(@Param("reviewStatus") reviewStatus: FoodContentDraftStatus, pageable: Pageable): Page<FoodContentDraft>
}
