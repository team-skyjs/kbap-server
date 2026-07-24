package com.kbap.app.api.admin

import com.kbap.application.foodimage.FoodImageSubmitResult

data class AdminFoodImageSubmitResponse(
    val submittedBatchCount: Int,
    val submittedFoodCount: Int,
) {
    companion object {
        fun from(result: FoodImageSubmitResult): AdminFoodImageSubmitResponse =
            AdminFoodImageSubmitResponse(
                submittedBatchCount = result.submittedBatchCount,
                submittedFoodCount = result.submittedFoodCount,
            )
    }
}
