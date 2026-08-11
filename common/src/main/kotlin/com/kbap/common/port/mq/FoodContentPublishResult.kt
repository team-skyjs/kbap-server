package com.kbap.common.port.mq

data class FoodContentPublishResult(
    val succeededOutboxIds: Set<Long>,
    val failedOutboxIds: Set<Long>,
)
