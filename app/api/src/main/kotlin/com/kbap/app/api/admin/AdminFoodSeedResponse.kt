package com.kbap.app.api.admin

import com.kbap.common.domain.food.dto.SeedIncompleteResult

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
