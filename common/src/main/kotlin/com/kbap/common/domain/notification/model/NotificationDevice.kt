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

    @Column(name = "token_invalid_at")
    var tokenInvalidAt: LocalDateTime? = null,
) : BaseEntity() {
    fun isTokenValid(): Boolean = tokenInvalidAt == null

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
        tokenInvalidAt = null
    }

    fun markTokenInvalid(now: LocalDateTime) {
        tokenInvalidAt = now
    }

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
