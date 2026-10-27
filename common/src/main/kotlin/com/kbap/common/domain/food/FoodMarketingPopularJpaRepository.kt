package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodMarketingPopular
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface FoodMarketingPopularJpaRepository : JpaRepository<FoodMarketingPopular, Long> {
    @Query("select m.foodId from FoodMarketingPopular m")
    fun findAllFoodIds(): List<Long>
}
