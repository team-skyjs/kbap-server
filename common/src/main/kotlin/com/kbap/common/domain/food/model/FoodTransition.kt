package com.kbap.common.domain.food.model

enum class FoodTransition {
    APPROVE,
    REJECT,
    RESUBMIT,
    UNPUBLISH,
}

class FoodTransitionException(
    val reason: String,
    val allowed: Set<FoodTransition>,
    message: String,
) : IllegalStateException(message)
