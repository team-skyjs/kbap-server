package com.kbap.api.admin

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.feedback.FeedbackJpaRepository
import com.kbap.common.domain.feedback.FeedbackReplyJpaRepository
import com.kbap.common.domain.feedback.model.Feedback
import com.kbap.common.domain.feedback.model.FeedbackReply
import com.kbap.common.domain.feedback.model.FeedbackStatus
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFeedbackService(
    private val feedbackRepository: FeedbackJpaRepository,
    private val replyRepository: FeedbackReplyJpaRepository,
    private val memberService: MemberService,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getFeedbackPage(status: String?, page: Int, size: Int): AdminFeedbackPageResult {
        val pageable = PageRequest.of(page, size)
        val result = when (val parsed = parseStatusFilter(status)) {
            null -> feedbackRepository.findAllByOrderByIdDesc(pageable)
            else -> feedbackRepository.findByFeedbackStatusOrderByIdDesc(parsed, pageable)
        }
        val replyCounts = result.content.associate { it.id to replyRepository.countByFeedbackId(it.id) }
        val nicknames = nicknamesOf(result.content)
        return AdminFeedbackPageResult(
            items = result.content.map { feedback ->
                AdminFeedbackPageResult.Item(
                    id = feedback.id,
                    contentPreview = feedback.content.take(CONTENT_PREVIEW_LENGTH),
                    hasImages = !feedback.imageRefs.isNullOrEmpty(),
                    reporterKey = reporterKeyOf(feedback),
                    memberId = feedback.memberId,
                    memberNickname = feedback.memberId?.let { nicknames[it] },
                    status = feedback.feedbackStatus.name,
                    replyCount = replyCounts[feedback.id]?.toInt() ?: 0,
                    createdAt = feedback.createdAt,
                    os = feedback.deviceInfo?.get("os"),
                    appVersion = feedback.deviceInfo?.get("appVersion"),
                )
            },
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getFeedback(id: Long): AdminFeedbackDetailResult {
        val feedback = feedbackRepository.findById(id).orElseThrow { BusinessException(ErrorCode.FEEDBACK_NOT_FOUND) }
        return AdminFeedbackDetailResult(
            id = feedback.id,
            content = feedback.content,
            imageUrls = feedback.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
            status = feedback.feedbackStatus.name,
            createdAt = feedback.createdAt,
            reporterKey = reporterKeyOf(feedback),
            memberId = feedback.memberId,
            memberNickname = feedback.memberId?.let { nicknamesOf(listOf(feedback))[it] },
            installationId = feedback.installationId,
            deviceInfo = feedback.deviceInfo.orEmpty(),
            userAgent = feedback.userAgent,
            receivedAt = feedback.createdAt,
            replies = replyRepository.findByFeedbackIdOrderByIdAsc(id).map {
                AdminFeedbackDetailResult.Reply(
                    id = it.id,
                    adminAccountId = it.adminAccountId,
                    content = it.content,
                    createdAt = it.createdAt,
                )
            },
        )
    }

    @Transactional
    fun reply(id: Long, adminAccountId: Long, content: String?): AdminFeedbackReplyResult {
        val feedback = feedbackRepository.findByIdForUpdate(id) ?: throw BusinessException(ErrorCode.FEEDBACK_NOT_FOUND)
        if (feedback.isClosed()) throw BusinessException(ErrorCode.FEEDBACK_CLOSED)
        val body = content?.trim().orEmpty()
        if (body.isEmpty() || body.length > Feedback.MAX_CONTENT_LENGTH) {
            throw BusinessException(ErrorCode.FEEDBACK_CONTENT_INVALID)
        }
        val saved = replyRepository.save(
            FeedbackReply(feedbackId = id, adminAccountId = adminAccountId, content = body),
        )
        feedback.answered()
        return AdminFeedbackReplyResult(
            id = saved.id,
            adminAccountId = saved.adminAccountId,
            feedbackId = id,
            content = saved.content,
            createdAt = saved.createdAt,
            feedbackStatus = feedback.feedbackStatus.name,
        )
    }

    @Transactional
    fun changeStatus(id: Long, status: String?): AdminFeedbackStatusResult {
        val feedback = feedbackRepository.findByIdForUpdate(id) ?: throw BusinessException(ErrorCode.FEEDBACK_NOT_FOUND)
        val next = status?.let { raw -> FeedbackStatus.entries.firstOrNull { it.name == raw } }
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        feedback.changeStatus(next)
        return AdminFeedbackStatusResult(id = id, status = next.name)
    }

    private fun parseStatusFilter(status: String?): FeedbackStatus? {
        val raw = status?.takeIf { it.isNotBlank() } ?: return FeedbackStatus.OPEN
        if (raw == ALL_STATUS) return null
        return FeedbackStatus.entries.firstOrNull { it.name == raw }
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    private fun nicknamesOf(rows: List<Feedback>): Map<Long, String?> =
        rows.mapNotNull { it.memberId }.distinct()
            .associateWith { memberService.getMemberOrNull(it)?.profile?.nickname }

    private fun reporterKeyOf(feedback: Feedback): String =
        feedback.installationId.takeIf { it.isNotBlank() }?.let { "inst:$it" }
            ?: feedback.memberId?.let { "member:$it" }
            ?: "unknown"

    companion object {
        const val ALL_STATUS = "ALL"
        const val CONTENT_PREVIEW_LENGTH = 300
        const val DEFAULT_PAGE_SIZE = 20
    }
}

data class AdminFeedbackPageResult(
    val items: List<Item>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
) {
    data class Item(
        val id: Long,
        val contentPreview: String,
        val hasImages: Boolean,
        val reporterKey: String,
        val memberId: Long?,
        val memberNickname: String?,
        val status: String,
        val replyCount: Int,
        val createdAt: LocalDateTime,
        val os: String?,
        val appVersion: String?,
    )
}

data class AdminFeedbackDetailResult(
    val id: Long,
    val content: String,
    val imageUrls: List<String>,
    val status: String,
    val createdAt: LocalDateTime,
    val reporterKey: String,
    val memberId: Long?,
    val memberNickname: String?,
    val installationId: String,
    val deviceInfo: Map<String, String>,
    val userAgent: String?,
    val receivedAt: LocalDateTime,
    val replies: List<Reply>,
) {
    data class Reply(
        val id: Long,
        val adminAccountId: Long,
        val content: String,
        val createdAt: LocalDateTime,
    )
}

data class AdminFeedbackReplyResult(
    val id: Long,
    val adminAccountId: Long,
    val feedbackId: Long,
    val content: String,
    val createdAt: LocalDateTime,
    val feedbackStatus: String,
)

data class AdminFeedbackStatusResult(
    val id: Long,
    val status: String,
)
