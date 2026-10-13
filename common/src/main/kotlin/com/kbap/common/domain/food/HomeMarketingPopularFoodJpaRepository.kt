package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.HomeMarketingPopularFood
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface HomeMarketingPopularFoodJpaRepository : JpaRepository<HomeMarketingPopularFood, Long> {
    @Query("select m.foodId from HomeMarketingPopularFood m")
    fun findAllFoodIds(): List<Long>
}
