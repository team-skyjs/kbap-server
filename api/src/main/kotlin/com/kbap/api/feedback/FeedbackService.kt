package com.kbap.api.feedback

import com.kbap.api.core.ApiHeaders
import com.kbap.api.food.FoodService
import com.kbap.api.image.UploadedImageService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.feedback.FeedbackJpaRepository
import com.kbap.common.domain.feedback.FeedbackReplyJpaRepository
import com.kbap.common.domain.feedback.model.Feedback
import com.kbap.common.domain.feedback.model.FeedbackReply
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.port.quota.InstallationQuotaStore
import com.kbap.common.util.ImageUrls
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackJpaRepository,
    private val replyRepository: FeedbackReplyJpaRepository,
    private val uploadedImageService: UploadedImageService,
    private val quotaStore: InstallationQuotaStore,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

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
        val requestId = UUID.randomUUID().toString()
        val holdsQuota = acquireQuota(installation, requestId)
        return try {
            transaction.execute {
                verifyImages(memberId, installation, imagePaths)
                verifyDailyQuota(installation)
                val saved = feedbackRepository.save(
                    Feedback.of(memberId, installation, body, imagePaths, sanitizeDeviceInfo(deviceInfo), userAgent),
                )
                CreateFeedbackResult(saved.id, saved.feedbackStatus.name, saved.createdAt)
            }!!
        } catch (e: Throwable) {
            if (holdsQuota) releaseQuota(installation, requestId)
            throw e
        }
    }

    private fun acquireQuota(installationId: String, requestId: String): Boolean {
        val acquired = try {
            quotaStore.tryAcquire(QUOTA_SCOPE, installationId, requestId, DAILY_LIMIT, QUOTA_WINDOW)
        } catch (e: RuntimeException) {
            log.warn("문의 한도 카운터(Redis)를 쓸 수 없어 DB 건수로만 판정한다 installationId={}", installationId, e)
            return false
        }
        if (!acquired) throw BusinessException(ErrorCode.FEEDBACK_RATE_LIMITED)
        return true
    }

    private fun releaseQuota(installationId: String, requestId: String) {
        runCatching { quotaStore.release(QUOTA_SCOPE, installationId, requestId) }
            .onFailure { log.warn("문의 한도 카운터 반납 실패 — 24시간 뒤 만료된다 installationId={}", installationId, it) }
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
            items = items.map { toItem(it, repliesByFeedback[it.id].orEmpty()) },
            nextCursor = items.lastOrNull()?.id?.takeIf { hasNext },
        )
    }

    @Transactional(readOnly = true)
    fun getMyFeedback(memberId: Long?, installationId: String?, id: Long): MyFeedbackPage.Item {
        val installation = requireInstallationId(installationId)
        val feedback = feedbackRepository.findMineById(id, installation, memberId)
            ?: throw BusinessException(ErrorCode.FEEDBACK_NOT_FOUND)
        return toItem(feedback, replyRepository.findByFeedbackIdInOrderByIdAsc(listOf(feedback.id)))
    }

    private fun toItem(feedback: Feedback, replies: List<FeedbackReply>) = MyFeedbackPage.Item(
        id = feedback.id,
        content = feedback.content,
        imageUrls = feedback.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
        status = feedback.feedbackStatus.name,
        createdAt = feedback.createdAt,
        replies = replies.map { MyFeedbackPage.Reply(id = it.id, content = it.content, createdAt = it.createdAt) },
    )

    private fun sanitizeDeviceInfo(deviceInfo: Map<String, String>?): Map<String, String>? =
        deviceInfo
            ?.filterKeys { it in DEVICE_INFO_KEYS }
            ?.mapValues { (_, value) -> value.take(MAX_DEVICE_VALUE_LENGTH) }
            ?.takeIf { it.isNotEmpty() }

    private fun requireInstallationId(raw: String?): String =
        raw?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)

    private fun verifyImages(memberId: Long?, installationId: String, imagePaths: List<String>?) {
        if (imagePaths.isNullOrEmpty()) return
        if (imagePaths.size > Feedback.MAX_IMAGE_COUNT) throw BusinessException(ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED)
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
        val since = LocalDateTime.now().minus(QUOTA_WINDOW)
        if (feedbackRepository.countByInstallationIdAndCreatedAtAfter(installationId, since) >= DAILY_LIMIT) {
            throw BusinessException(ErrorCode.FEEDBACK_RATE_LIMITED)
        }
    }

    companion object {
        const val DAILY_LIMIT = 20
        const val QUOTA_SCOPE = "feedback"
        val QUOTA_WINDOW: Duration = Duration.ofDays(1)
        const val PAGE_SIZE = 20
        const val MAX_DEVICE_VALUE_LENGTH = 100
        val DEVICE_INFO_KEYS = setOf(
            "os",
            "osVersion",
            "appVersion",
            "buildNumber",
            "runtimeVersion",
            "deviceModel",
            "locale",
            "lang",
            "timezone",
        )
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
