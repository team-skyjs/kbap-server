package com.meogo.api.application.food

data class GetFoodDetailInput(
    val menuName: String,
    val lang: String? = null,
)
