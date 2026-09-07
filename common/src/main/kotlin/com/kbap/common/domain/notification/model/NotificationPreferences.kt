package com.kbap.common.domain.notification.model

data class NotificationPreferences(
    val helpful: Boolean = true,
    val reviewReminder: Boolean = true,
) {
    companion object {
        val DEFAULT = NotificationPreferences()
    }
}
