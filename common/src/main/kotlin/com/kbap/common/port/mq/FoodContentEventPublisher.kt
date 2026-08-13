package com.kbap.common.port.mq

fun interface FoodContentEventPublisher {
    fun publish(events: List<FoodContentEvent>): FoodContentPublishResult
}
