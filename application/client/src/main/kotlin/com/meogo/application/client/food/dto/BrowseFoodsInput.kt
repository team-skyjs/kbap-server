package com.meogo.application.client.food.dto

data class BrowseFoodsInput(
    val cursor: Long?,
    val lang: String?,
    val memberId: Long? = null,
)
