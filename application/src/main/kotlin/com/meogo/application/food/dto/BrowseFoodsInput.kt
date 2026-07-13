package com.meogo.application.food.dto

data class BrowseFoodsInput(
    val cursor: Long?,
    val lang: String?,
    val memberId: Long? = null,
)
