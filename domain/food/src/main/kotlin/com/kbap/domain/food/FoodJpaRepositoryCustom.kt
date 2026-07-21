package com.kbap.domain.food

import com.kbap.domain.food.model.Food

internal interface FoodJpaRepositoryCustom {
    fun upsertIncomplete(foods: List<Food>)
}
