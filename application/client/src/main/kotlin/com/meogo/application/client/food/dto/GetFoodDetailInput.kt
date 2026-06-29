package com.meogo.application.client.food.dto

data class GetFoodDetailInput(
    val menuName: String,
    val lang: String? = null,
)
