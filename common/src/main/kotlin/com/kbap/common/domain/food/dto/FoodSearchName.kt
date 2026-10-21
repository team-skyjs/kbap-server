package com.kbap.common.domain.food.dto

data class FoodSearchName(
    val id: Long,
    val koreanName: String,
    val displayName: String,
    val nameTranslations: Map<String, String>,
)
