package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodContentReviewField
import jakarta.validation.constraints.NotNull

data class AdminFoodContentReviewResultRequest(
    @field:NotNull
    val passed: Boolean? = null,
    val rejectedFields: Set<FoodContentReviewField>? = null,
    val reason: String? = null,
)
