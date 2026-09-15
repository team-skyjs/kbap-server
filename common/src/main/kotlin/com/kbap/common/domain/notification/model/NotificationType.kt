package com.kbap.common.domain.notification.model

enum class NotificationType(val marketing: Boolean) {
    HELPFUL(false),
    SCAN_SUGGESTION(true),
    REVIEW_REMINDER(false),
    NEWS(true),
    MEAL_TIME(true),
    ;

    val channelId: String
        get() = if (marketing) MARKETING_CHANNEL else DEFAULT_CHANNEL

    companion object {
        const val MARKETING_CHANNEL = "news"
        const val DEFAULT_CHANNEL = "default"
    }
}
