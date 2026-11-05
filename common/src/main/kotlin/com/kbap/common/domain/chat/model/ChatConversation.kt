package com.kbap.common.domain.chat.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "chat_conversation")
class ChatConversation(
    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "guest_installation_id", length = 36)
    var guestInstallationId: String? = null,

    @Column(name = "user_last_read_message_id")
    var userLastReadMessageId: Long? = null,

    @Column(name = "operator_last_read_message_id")
    var operatorLastReadMessageId: Long? = null,

    @Column(name = "last_message_at")
    var lastMessageAt: LocalDateTime? = null,

    @Column(name = "last_user_message_at")
    var lastUserMessageAt: LocalDateTime? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_device_info", columnDefinition = "json")
    var lastDeviceInfo: Map<String, String>? = null,
) : BaseEntity() {
    fun isOwnedBy(memberId: Long?, installationId: String): Boolean =
        if (memberId != null) this.memberId == memberId else this.memberId == null && guestInstallationId == installationId

    fun recordUserMessage(message: ChatMessage, deviceInfo: Map<String, String>?) {
        lastMessageAt = message.createdAt
        lastUserMessageAt = message.createdAt
        if (deviceInfo != null) lastDeviceInfo = deviceInfo
        markUserRead(message.id)
    }

    fun recordOperatorMessage(message: ChatMessage) {
        lastMessageAt = message.createdAt
        markOperatorRead(message.id)
    }

    fun markUserRead(messageId: Long) {
        if ((userLastReadMessageId ?: 0) < messageId) userLastReadMessageId = messageId
    }

    fun markOperatorRead(messageId: Long) {
        if ((operatorLastReadMessageId ?: 0) < messageId) operatorLastReadMessageId = messageId
    }

    companion object {
        fun forMember(memberId: Long) = ChatConversation(memberId = memberId)

        fun forGuest(installationId: String) = ChatConversation(guestInstallationId = installationId)
    }
}
