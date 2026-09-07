package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "notification_setting",
    uniqueConstraints = [UniqueConstraint(name = "uk_notification_setting_member", columnNames = ["member_id"])],
)
class NotificationSetting(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "helpful", nullable = false)
    var helpful: Boolean = true,

    @Column(name = "review_reminder", nullable = false)
    var reviewReminder: Boolean = true,

    @Column(name = "marketing", nullable = false)
    var marketing: Boolean = false,

    @Column(name = "marketing_consent_version", length = 20)
    var marketingConsentVersion: String? = null,

    @Column(name = "marketing_opt_in_at")
    var marketingOptInAt: LocalDateTime? = null,
) : BaseEntity() {
    fun preferences() = NotificationPreferences(
        helpful = helpful,
        reviewReminder = reviewReminder,
        marketing = marketing,
        marketingConsentVersion = marketingConsentVersion,
        marketingOptInAt = marketingOptInAt,
    )

    fun updateHelpful(enabled: Boolean) {
        helpful = enabled
    }

    fun updateReviewReminder(enabled: Boolean) {
        reviewReminder = enabled
    }

    fun updateMarketing(enabled: Boolean, consentVersion: String?, now: LocalDateTime) {
        val transition = MarketingConsent.transition(
            current = MarketingConsent(marketing, marketingConsentVersion, marketingOptInAt),
            enabled = enabled,
            consentVersion = consentVersion,
            now = now,
        )
        marketing = transition.enabled
        marketingConsentVersion = transition.consentVersion
        marketingOptInAt = transition.optInAt
    }

    fun isMarketingAllowed(requiredVersion: String): Boolean =
        MarketingConsent(marketing, marketingConsentVersion, marketingOptInAt).allows(requiredVersion)

    companion object {
        fun defaultFor(memberId: Long) = NotificationSetting(memberId = memberId)

        fun inheritFrom(memberId: Long, device: NotificationDevice) = NotificationSetting(
            memberId = memberId,
            marketing = device.marketing,
            marketingConsentVersion = device.marketingConsentVersion,
            marketingOptInAt = device.marketingOptInAt,
        )
    }
}
