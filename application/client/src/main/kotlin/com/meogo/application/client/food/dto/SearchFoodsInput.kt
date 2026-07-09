package com.meogo.application.client.food.dto

data class SearchFoodsInput(
    val keyword: String?,
    val cursor: Long?,
    val lang: String?,
)
