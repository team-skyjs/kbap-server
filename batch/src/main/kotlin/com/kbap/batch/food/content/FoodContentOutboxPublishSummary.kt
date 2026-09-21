package com.kbap.batch.outbox

data class FoodContentOutboxPublishSummary(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
)
