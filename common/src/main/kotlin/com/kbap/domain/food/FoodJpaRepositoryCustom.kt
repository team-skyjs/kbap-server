package com.kbap.domain.food

import com.kbap.domain.food.model.Food

interface FoodJpaRepositoryCustom {
    fun upsertIncomplete(foods: List<Food>)
}
