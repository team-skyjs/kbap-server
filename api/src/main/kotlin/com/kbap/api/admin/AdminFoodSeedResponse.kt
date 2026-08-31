package com.kbap.api.admin

data class AdminFoodSeedResponse(
    val requested: Int,
    val created: Int,
    val skipped: Int,
) {
    companion object {
        fun from(result: SeedIncompleteResult): AdminFoodSeedResponse =
            AdminFoodSeedResponse(
                requested = result.requested,
                created = result.created,
                skipped = result.skipped,
            )
    }
}
