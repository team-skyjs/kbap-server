package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "notification_device",
    uniqueConstraints = [UniqueConstraint(name = "uk_notification_device_installation", columnNames = ["installation_id"])],
)
class NotificationDevice(
    @Column(name = "installation_id", nullable = false, length = 36)
    var installationId: String = "",

    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "expo_token", nullable = false, length = 255)
    var expoToken: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, columnDefinition = "ENUM('IOS','ANDROID')")
    var platform: DevicePlatform = DevicePlatform.IOS,

    @Column(name = "lang", nullable = false, length = 10)
    var lang: String = "",

    @Column(name = "marketing", nullable = false)
    var marketing: Boolean = false,

    @Column(name = "marketing_consent_version", length = 20)
    var marketingConsentVersion: String? = null,

    @Column(name = "marketing_opt_in_at")
    var marketingOptInAt: LocalDateTime? = null,
) : BaseEntity() {
    fun linkMember(memberId: Long) {
        this.memberId = memberId
    }

    fun unlinkMember() {
        memberId = null
    }

    fun renew(expoToken: String, platform: DevicePlatform, lang: String) {
        this.expoToken = expoToken
        this.platform = platform
        this.lang = lang
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
        fun register(
            installationId: String,
            expoToken: String,
            platform: DevicePlatform,
            lang: String,
            memberId: Long? = null,
        ) = NotificationDevice(
            installationId = installationId,
            expoToken = expoToken,
            platform = platform,
            lang = lang,
            memberId = memberId,
        )
    }
}
