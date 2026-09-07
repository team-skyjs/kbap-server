package com.kbap.common.domain.notification.model

import java.time.LocalDateTime

data class NotificationPreferences(
    val helpful: Boolean = true,
    val reviewReminder: Boolean = true,
    val marketing: Boolean = false,
    val marketingConsentVersion: String? = null,
    val marketingOptInAt: LocalDateTime? = null,
) {
    companion object {
        val DEFAULT = NotificationPreferences()
    }
}
