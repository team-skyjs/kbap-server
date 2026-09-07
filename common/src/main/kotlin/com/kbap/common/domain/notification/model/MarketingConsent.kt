package com.kbap.common.domain.notification.model

import java.time.LocalDateTime

data class MarketingConsent(
    val enabled: Boolean,
    val consentVersion: String?,
    val optInAt: LocalDateTime?,
) {
    fun allows(requiredVersion: String): Boolean =
        enabled && optInAt != null && consentVersion != null && consentVersion >= requiredVersion

    companion object {
        val NONE = MarketingConsent(enabled = false, consentVersion = null, optInAt = null)

        fun transition(current: MarketingConsent, enabled: Boolean, consentVersion: String?, now: LocalDateTime): MarketingConsent =
            when {
                !enabled -> NONE
                !current.enabled || current.consentVersion != consentVersion ->
                    MarketingConsent(enabled = true, consentVersion = consentVersion, optInAt = now)
                else -> current
            }
    }
}
