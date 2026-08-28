package com.kbap.api.admin

data class SeedIncompleteResult(
    val requested: Int,
    val created: Int,
    val skipped: Int,
    val createdIds: List<Long> = emptyList(),
    val skippedNames: List<String> = emptyList(),
    val blockedByDeletedNames: List<String> = emptyList(),
)
