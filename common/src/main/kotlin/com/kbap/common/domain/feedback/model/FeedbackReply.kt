package com.kbap.common.domain.feedback.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "feedback_reply",
    indexes = [Index(name = "idx_feedback_reply_feedback", columnList = "feedback_id")],
)
class FeedbackReply(
    @Column(name = "feedback_id", nullable = false)
    val feedbackId: Long = 0,

    @Column(name = "admin_account_id", nullable = false)
    val adminAccountId: Long = 0,

    @Column(nullable = false, columnDefinition = "text")
    val content: String = "",
) : BaseEntity()
