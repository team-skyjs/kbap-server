package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodReviewField
import jakarta.validation.constraints.NotNull

data class AdminFoodReviewResultRequest(
    @field:NotNull
    val passed: Boolean? = null,
    val rejectedFields: Set<FoodReviewField>? = null,
    val reason: String? = null,
)
