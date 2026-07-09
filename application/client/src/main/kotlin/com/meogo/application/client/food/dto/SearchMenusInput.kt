package com.meogo.application.client.food.dto

data class SearchMenusInput(
    val keyword: String?,
    val cursor: Long?,
    val lang: String?,
)
