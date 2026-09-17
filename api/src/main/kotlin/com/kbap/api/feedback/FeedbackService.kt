package com.kbap.api.feedback

import com.kbap.api.core.ApiHeaders
import com.kbap.api.food.FoodService
import com.kbap.api.image.UploadedImageService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.feedback.FeedbackJpaRepository
import com.kbap.common.domain.feedback.FeedbackReplyJpaRepository
import com.kbap.common.domain.feedback.model.Feedback
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackJpaRepository,
    private val replyRepository: FeedbackReplyJpaRepository,
    private val uploadedImageService: UploadedImageService,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional
    fun createFeedback(
        memberId: Long?,
        installationId: String?,
        content: String?,
        imagePaths: List<String>?,
        deviceInfo: Map<String, String>?,
        userAgent: String?,
    ): CreateFeedbackResult {
        val installation = requireInstallationId(installationId)
        val body = content?.trim().orEmpty()
        if (body.isEmpty() || body.length > Feedback.MAX_CONTENT_LENGTH) {
            throw BusinessException(ErrorCode.FEEDBACK_CONTENT_INVALID)
        }
        verifyImages(memberId, installation, imagePaths)
        verifyDailyQuota(installation)

        val saved = feedbackRepository.save(
            Feedback.of(memberId, installation, body, imagePaths, deviceInfo, userAgent),
        )
        return CreateFeedbackResult(saved.id, saved.feedbackStatus.name, saved.createdAt)
    }

    @Transactional(readOnly = true)
    fun getMyFeedbackPage(memberId: Long?, installationId: String?, cursor: Long?, size: Int): MyFeedbackPage {
        val installation = requireInstallationId(installationId)
        val rows = feedbackRepository.findMinePage(installation, memberId, cursor, PageRequest.of(0, size + 1))
        val hasNext = rows.size > size
        val items = rows.take(size)
        val repliesByFeedback = replyRepository.findByFeedbackIdInOrderByIdAsc(items.map { it.id })
            .groupBy { it.feedbackId }
        return MyFeedbackPage(
            items = items.map { feedback ->
                MyFeedbackPage.Item(
                    id = feedback.id,
                    content = feedback.content,
                    imageUrls = feedback.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                    status = feedback.feedbackStatus.name,
                    createdAt = feedback.createdAt,
                    replies = repliesByFeedback[feedback.id].orEmpty().map {
                        MyFeedbackPage.Reply(id = it.id, content = it.content, createdAt = it.createdAt)
                    },
                )
            },
            nextCursor = items.lastOrNull()?.id?.takeIf { hasNext },
        )
    }

    private fun requireInstallationId(raw: String?): String =
        raw?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)

    private fun verifyImages(memberId: Long?, installationId: String, imagePaths: List<String>?) {
        if (imagePaths.isNullOrEmpty()) return
        if (imagePaths.size > Feedback.MAX_IMAGE_COUNT) throw BusinessException(ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED)
        // 회원이면 본인 업로드, 게스트면 같은 기기 업로드까지 인정한다 — 게스트로 올린 사진이 가입 후에도 통과한다.
        val owned = uploadedImageService.ownsAllImages(
            memberId = memberId,
            paths = imagePaths,
            purpose = UploadPurpose.FEEDBACK,
            installationId = installationId,
        )
        if (!owned) {
            throw BusinessException(ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED)
        }
    }

    private fun verifyDailyQuota(installationId: String) {
        val since = LocalDateTime.now().minusDays(1)
        if (feedbackRepository.countByInstallationIdAndCreatedAtAfter(installationId, since) >= DAILY_LIMIT) {
            throw BusinessException(ErrorCode.FEEDBACK_RATE_LIMITED)
        }
    }

    companion object {
        const val DAILY_LIMIT = 20
        const val PAGE_SIZE = 20
    }
}

data class CreateFeedbackResult(
    val id: Long,
    val status: String,
    val createdAt: LocalDateTime,
)

data class MyFeedbackPage(
    val items: List<Item>,
    val nextCursor: Long?,
) {
    data class Item(
        val id: Long,
        val content: String,
        val imageUrls: List<String>,
        val status: String,
        val createdAt: LocalDateTime,
        val replies: List<Reply>,
    )

    data class Reply(
        val id: Long,
        val content: String,
        val createdAt: LocalDateTime,
    )
}
