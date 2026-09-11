package com.kbap.common.domain.notification.model

object NotificationConsents {
    fun isMarketingEnabled(open: List<NotificationConsent>, requiredVersion: Int): Boolean =
        NotificationConsentType.entries.all { type -> open.any { it.consentType == type && it.allows(requiredVersion) } }
}
