package com.kbap.common.domain.feedback

import com.kbap.common.domain.feedback.model.FeedbackReply
import org.springframework.data.jpa.repository.JpaRepository

interface FeedbackReplyJpaRepository : JpaRepository<FeedbackReply, Long> {
    fun findByFeedbackIdOrderByIdAsc(feedbackId: Long): List<FeedbackReply>

    fun findByFeedbackIdInOrderByIdAsc(feedbackIds: List<Long>): List<FeedbackReply>

    fun countByFeedbackId(feedbackId: Long): Long
}
