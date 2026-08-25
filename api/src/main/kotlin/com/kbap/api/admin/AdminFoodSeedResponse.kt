package com.kbap.api.admin

data class AdminFoodSeedResponse(
    val requested: Int,
    val created: Int,
    val skipped: Int,
    val createdIds: List<Long>,
    val skippedNames: List<String>,
    val blockedByDeletedNames: List<String>,
) {
    companion object {
        fun from(result: SeedIncompleteResult): AdminFoodSeedResponse =
            AdminFoodSeedResponse(
                requested = result.requested,
                created = result.created,
                skipped = result.skipped,
                createdIds = result.createdIds,
                skippedNames = result.skippedNames,
                blockedByDeletedNames = result.blockedByDeletedNames,
            )
    }
}
