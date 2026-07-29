package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.Food

interface FoodRepositoryCustom {
    fun upsertIncomplete(foods: List<Food>)
}
