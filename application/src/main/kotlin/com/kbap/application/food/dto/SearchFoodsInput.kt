package com.kbap.application.food.dto

data class SearchFoodsInput(
    val keyword: String,
    val cursor: Long?,
    val lang: String?,
    val memberId: Long? = null,
)
