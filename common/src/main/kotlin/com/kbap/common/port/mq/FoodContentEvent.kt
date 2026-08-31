package com.kbap.common.port.mq

data class FoodContentEvent(
    val outboxId: Long,
    val foodId: Long,
    val scannedName: String,
)
