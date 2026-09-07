package com.kbap.common.domain.notification.model

data class NotificationPreferences(
    val activity: Boolean = true,
    val mealTime: Boolean = true,
) {
    companion object {
        val DEFAULT = NotificationPreferences()
    }
}
