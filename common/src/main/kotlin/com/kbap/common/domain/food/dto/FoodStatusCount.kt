package com.kbap.common.domain.food.dto

import com.kbap.common.domain.food.model.FoodContentStatus

data class FoodStatusCount(
    val status: FoodContentStatus,
    val count: Long,
)
