package com.kbap.common.domain.chat.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "chat_message")
class ChatMessage(
    @Column(name = "conversation_id", nullable = false)
    var conversationId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, columnDefinition = "ENUM('USER','OPERATOR')")
    var authorRole: ChatAuthorRole = ChatAuthorRole.USER,

    @Column(name = "admin_account_id")
    var adminAccountId: Long? = null,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_refs", columnDefinition = "json")
    var imageRefs: List<String>? = null,

    @Column(name = "legacy_feedback_id")
    var legacyFeedbackId: Long? = null,

    @Column(name = "legacy_reply_id")
    var legacyReplyId: Long? = null,
) : BaseEntity() {
    companion object {
        const val MAX_CONTENT_LENGTH = 2000
        const val MAX_IMAGE_COUNT = 3

        fun fromUser(conversationId: Long, content: String, imageRefs: List<String>?, legacyFeedbackId: Long? = null) =
            ChatMessage(conversationId = conversationId, authorRole = ChatAuthorRole.USER, content = content, imageRefs = imageRefs?.takeIf { it.isNotEmpty() }, legacyFeedbackId = legacyFeedbackId)

        fun fromOperator(conversationId: Long, adminAccountId: Long, content: String, legacyFeedbackId: Long? = null, legacyReplyId: Long? = null) =
            ChatMessage(conversationId = conversationId, authorRole = ChatAuthorRole.OPERATOR, adminAccountId = adminAccountId, content = content, legacyFeedbackId = legacyFeedbackId, legacyReplyId = legacyReplyId)
    }
}
