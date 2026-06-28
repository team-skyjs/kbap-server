package com.meogo.api.application.food.dto

data class GetFoodDetailInput(
    val menuName: String,
    val lang: String? = null,
)
