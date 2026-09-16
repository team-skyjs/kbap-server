package com.kbap.api.admin

import com.kbap.api.food.FoodImageSubmitResult

data class AdminFoodImageSubmitResponse(
    val submittedBatchCount: Int,
    val submittedFoodCount: Int,
    val submittedCount: Int,
    val skippedInProgress: List<Long>,
) {
    companion object {
        fun from(result: FoodImageSubmitResult): AdminFoodImageSubmitResponse =
            AdminFoodImageSubmitResponse(
                submittedBatchCount = result.submittedBatchCount,
                submittedFoodCount = result.submittedFoodCount,
                submittedCount = result.submittedFoodCount,
                skippedInProgress = result.skippedInProgress,
            )
    }
}
