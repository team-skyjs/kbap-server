package com.kbap.common.domain.notification.model

enum class NotificationType(val marketingByDefault: Boolean) {
    HELPFUL(false),
    SCAN_SUGGESTION(true),
    REVIEW_REMINDER(false),
    NOTICE(false),
    MEAL_TIME(false),
}
