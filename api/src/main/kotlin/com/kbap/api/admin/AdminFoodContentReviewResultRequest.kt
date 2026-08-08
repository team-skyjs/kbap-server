package com.kbap.api.admin

import jakarta.validation.constraints.NotNull

data class AdminFoodContentReviewResultRequest(
    @field:NotNull
    val passed: Boolean? = null,
    val reason: String? = null,
)
