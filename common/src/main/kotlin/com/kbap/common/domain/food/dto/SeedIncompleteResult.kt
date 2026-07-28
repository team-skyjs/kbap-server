package com.kbap.common.domain.food.dto

data class SeedIncompleteResult(
    val requested: Int,
    val created: Int,
    val skipped: Int,
)
