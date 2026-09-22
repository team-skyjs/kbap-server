package com.kbap.common.domain.notification.model

import java.time.LocalTime

enum class MealSlot(val startTime: LocalTime) {
    LUNCH(LocalTime.of(11, 0)),
    DINNER(LocalTime.of(17, 0)),
}
