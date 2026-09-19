package com.kbap.common.domain.feedback.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "feedback",
    indexes = [
        Index(name = "idx_feedback_installation", columnList = "installation_id"),
        Index(name = "idx_feedback_member", columnList = "member_id"),
        Index(name = "idx_feedback_status_created", columnList = "feedback_status, created_at"),
    ],
)
class Feedback(
    @Column(name = "member_id")
    val memberId: Long? = null,

    @Column(name = "installation_id", nullable = false, length = MAX_INSTALLATION_ID_LENGTH)
    val installationId: String = "",

    @Column(nullable = false, columnDefinition = "text")
    val content: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_refs")
    val imageRefs: List<String>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device_info")
    val deviceInfo: Map<String, String>? = null,

    @Column(name = "user_agent", length = MAX_USER_AGENT_LENGTH)
    val userAgent: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_status", nullable = false, columnDefinition = "ENUM('OPEN','ANSWERED','CLOSED')")
    var feedbackStatus: FeedbackStatus = FeedbackStatus.OPEN,
) : BaseEntity() {
    fun answered() {
        if (feedbackStatus == FeedbackStatus.OPEN) feedbackStatus = FeedbackStatus.ANSWERED
    }

    fun changeStatus(next: FeedbackStatus) {
        feedbackStatus = next
    }

    fun isClosed(): Boolean = feedbackStatus == FeedbackStatus.CLOSED

    companion object {
        const val MAX_CONTENT_LENGTH = 2000
        const val MAX_IMAGE_COUNT = 3
        const val MAX_INSTALLATION_ID_LENGTH = 36
        const val MAX_USER_AGENT_LENGTH = 255

        fun of(
            memberId: Long?,
            installationId: String,
            content: String,
            imageRefs: List<String>?,
            deviceInfo: Map<String, String>?,
            userAgent: String?,
        ): Feedback = Feedback(
            memberId = memberId,
            installationId = installationId,
            content = content,
            imageRefs = imageRefs?.takeIf { it.isNotEmpty() },
            deviceInfo = deviceInfo?.takeIf { it.isNotEmpty() },
            userAgent = userAgent?.take(MAX_USER_AGENT_LENGTH),
        )
    }
}
