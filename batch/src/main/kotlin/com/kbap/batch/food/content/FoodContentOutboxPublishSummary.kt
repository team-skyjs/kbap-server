package com.kbap.batch.food.content

data class FoodContentOutboxPublishSummary(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
)
