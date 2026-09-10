package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "notification")
class Notification(
    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "installation_id", length = 36)
    var installationId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    var type: NotificationType = NotificationType.NEWS,

    @Column(name = "title", nullable = false, length = 200)
    var title: String = "",

    @Column(name = "body", nullable = false, length = 1000)
    var body: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data")
    var data: Map<String, Any>? = null,

    @Column(name = "read_at")
    var readAt: LocalDateTime? = null,
) : BaseEntity() {
    fun markRead(now: LocalDateTime) {
        if (readAt == null) readAt = now
    }

    fun isRead(): Boolean = readAt != null

    companion object {
        fun forMember(memberId: Long, type: NotificationType, title: String, body: String, data: Map<String, Any>?) =
            Notification(memberId = memberId, type = type, title = title, body = body, data = data)

        fun forInstallation(installationId: String, type: NotificationType, title: String, body: String, data: Map<String, Any>?) =
            Notification(installationId = installationId, type = type, title = title, body = body, data = data)

        fun forMemberDevice(
            memberId: Long,
            installationId: String,
            type: NotificationType,
            title: String,
            body: String,
            data: Map<String, Any>?,
        ) = Notification(memberId = memberId, installationId = installationId, type = type, title = title, body = body, data = data)
    }
}
