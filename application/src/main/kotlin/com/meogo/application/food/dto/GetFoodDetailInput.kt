package com.meogo.application.food.dto

data class GetFoodDetailInput(
    val foodId: Long,
    val lang: String? = null,
    val memberId: Long? = null,
)
