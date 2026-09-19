package com.kbap.common.domain.notification.model

enum class NotificationType(val marketing: Boolean) {
    HELPFUL(false),
    SCAN_SUGGESTION(true),
    REVIEW_REMINDER(false),
    NEWS(true),
    MEAL_TIME(true),
    ;

    val channelId: String
        get() = when (this) {
            HELPFUL, REVIEW_REMINDER -> "activity"
            SCAN_SUGGESTION, NEWS, MEAL_TIME -> "news"
        }
}
