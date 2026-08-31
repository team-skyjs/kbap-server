package com.kbap.api.admin

data class SeedIncompleteResult(
    val requested: Int,
    val created: Int,
    val skipped: Int,
)
