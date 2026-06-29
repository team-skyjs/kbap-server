package com.meogo.application.food.dto

data class GetFoodDetailInput(
    val menuName: String,
    val lang: String? = null,
)
