package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "notification_consent")
class NotificationConsent(
    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "installation_id", length = 36)
    var installationId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30)
    var consentType: NotificationConsentType = NotificationConsentType.MARKETING_RECEIVE,

    @Column(name = "consent_version", nullable = false, columnDefinition = "SMALLINT UNSIGNED")
    var consentVersion: Int = 0,

    @Column(name = "granted_at", nullable = false)
    var grantedAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null,
) : BaseEntity() {
    fun isOpen(): Boolean = revokedAt == null

    fun allows(requiredVersion: Int): Boolean = isOpen() && consentVersion >= requiredVersion

    fun revoke(now: LocalDateTime) {
        check(isOpen()) { "이미 철회된 동의: id=$id" }
        revokedAt = now
    }

    companion object {
        fun grantForMember(memberId: Long, installationId: String?, type: NotificationConsentType, consentVersion: Int, now: LocalDateTime) =
            NotificationConsent(
                memberId = memberId,
                installationId = installationId,
                consentType = type,
                consentVersion = consentVersion,
                grantedAt = now,
            )
    }
}
