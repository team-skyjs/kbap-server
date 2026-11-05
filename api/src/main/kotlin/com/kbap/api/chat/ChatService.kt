package com.kbap.api.chat

import com.kbap.api.core.ApiHeaders
import com.kbap.api.image.UploadedImageService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.chat.ChatConversationJpaRepository
import com.kbap.common.domain.chat.ChatMessageJpaRepository
import com.kbap.common.domain.chat.model.ChatAuthorRole
import com.kbap.common.domain.chat.model.ChatConversation
import com.kbap.common.domain.chat.model.ChatMessage
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.port.quota.InstallationQuotaStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Service
class ChatService(
    private val conversations: ChatConversationJpaRepository,
    private val messages: ChatMessageJpaRepository,
    private val uploadedImageService: UploadedImageService,
    private val quotaStore: InstallationQuotaStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val views: ChatMessageViews,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.chat.daily-limit:100}") private val dailyLimit: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val separateTransaction = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun getMessagePage(memberId: Long?, installationId: String?, before: Long?, limit: Int): ChatMessagePage {
        val installation = requireInstallationId(installationId)
        return transaction.execute {
            val conversation = ownConversation(memberId, installation)
                ?: return@execute ChatMessagePage(conversationId = null, items = emptyList(), nextCursor = null, hasMore = false)
            views.page(conversation.id, before, limit)
        }!!
    }

    fun sendMessage(memberId: Long?, installationId: String?, content: String?, imagePaths: List<String>?, deviceInfo: Map<String, String>?): ChatMessageView {
        val installation = requireInstallationId(installationId)
        val body = content?.trim().orEmpty()
        if (body.isEmpty() || body.length > ChatMessage.MAX_CONTENT_LENGTH) throw BusinessException(ErrorCode.FEEDBACK_CONTENT_INVALID)
        verifyImages(memberId, installation, imagePaths)
        val requestId = UUID.randomUUID().toString()
        val holdsQuota = acquireQuota(installation, requestId, recordedAtMillis(memberId, installation))
        return try {
            ensureConversation(memberId, installation)
            val (message, conversation) = saveUserMessage(memberId, installation, body, imagePaths, sanitize(deviceInfo))
            eventPublisher.publishEvent(ChatUserMessageSent(conversation.id, message.id, conversation.memberId, message.content, message.imageRefs.orEmpty().size))
            views.of(message)
        } catch (e: Throwable) {
            if (holdsQuota) runCatching { quotaStore.release(QUOTA_SCOPE, installation, requestId) }
            throw e
        }
    }

    fun appendLegacyFeedback(memberId: Long?, installationId: String, feedbackId: Long, content: String, imageRefs: List<String>?, deviceInfo: Map<String, String>?) {
        val conversation = ownConversation(memberId, installationId)!!
        val message = messages.saveAndFlush(ChatMessage.fromUser(conversation.id, content, imageRefs, legacyFeedbackId = feedbackId))
        conversation.recordUserMessage(message, deviceInfo)
    }

    fun markRead(memberId: Long?, installationId: String?, lastMessageId: Long) {
        val installation = requireInstallationId(installationId)
        transaction.executeWithoutResult {
            val conversation = ownConversation(memberId, installation) ?: return@executeWithoutResult
            val latest = messages.findFirstByConversationIdOrderByIdDesc(conversation.id)?.id ?: return@executeWithoutResult
            conversation.markUserRead(minOf(lastMessageId, latest))
        }
    }

    fun getUnread(memberId: Long?, installationId: String?): ChatUnread {
        val installation = requireInstallationId(installationId)
        return readTransaction.execute {
            val conversation = ownedReadOnly(memberId, installation) ?: return@execute ChatUnread(0, null)
            val unread = messages.countByConversationIdAndAuthorRoleAndIdGreaterThan(conversation.id, ChatAuthorRole.OPERATOR, conversation.userLastReadMessageId ?: 0)
            val last = messages.findFirstByConversationIdAndAuthorRoleOrderByIdDesc(conversation.id, ChatAuthorRole.OPERATOR)
            ChatUnread(unread, last?.let { ChatUnread.Preview(it.content.take(PREVIEW_LENGTH), it.createdAt) })
        }!!
    }

    private fun saveUserMessage(memberId: Long?, installation: String, body: String, imagePaths: List<String>?, deviceInfo: Map<String, String>?): Pair<ChatMessage, ChatConversation> =
        transaction.execute {
            val conversation = ownConversation(memberId, installation)!!
            val message = messages.saveAndFlush(ChatMessage.fromUser(conversation.id, body, imagePaths))
            conversation.recordUserMessage(message, deviceInfo)
            message to conversation
        }!!

    private fun ownConversation(memberId: Long?, installationId: String): ChatConversation? =
        if (memberId != null) {
            conversations.findIdByMemberId(memberId)?.let { conversations.findByIdForUpdate(it) }
        } else {
            conversations.findGuestId(installationId)?.let { conversations.findByIdForUpdate(it) }
        }

    private fun ownedReadOnly(memberId: Long?, installationId: String): ChatConversation? =
        if (memberId != null) conversations.findByMemberId(memberId) else conversations.findByGuestInstallationIdAndMemberIdIsNull(installationId)

    fun ensureConversation(memberId: Long?, installationId: String) {
        separateTransaction.executeWithoutResult {
            if (memberId != null) {
                if (conversations.findIdByMemberId(memberId) == null) conversations.insertIgnoreForMember(memberId)
            } else if (conversations.findGuestId(installationId) == null) {
                conversations.insertIgnoreForGuest(installationId)
            }
        }
    }

    private fun recordedAtMillis(memberId: Long?, installationId: String): List<Long> {
        val since = LocalDateTime.now().minus(QUOTA_WINDOW)
        val recorded = readTransaction.execute {
            ownedReadOnly(memberId, installationId)?.let { messages.findUserMessageTimesSince(it.id, since) }.orEmpty()
        }!!
        if (recorded.size >= dailyLimit) throw BusinessException(ErrorCode.CHAT_RATE_LIMITED)
        return recorded.map { it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    }

    private fun acquireQuota(installationId: String, requestId: String, recordedAtMillis: List<Long>): Boolean {
        val acquired = try {
            quotaStore.tryAcquire(QUOTA_SCOPE, installationId, requestId, dailyLimit, QUOTA_WINDOW, recordedAtMillis)
        } catch (e: RuntimeException) {
            log.warn("채팅 한도 카운터(Redis)를 쓸 수 없어 DB 건수로만 판정한다 installationId={}", installationId, e)
            return false
        }
        if (!acquired) throw BusinessException(ErrorCode.CHAT_RATE_LIMITED)
        return true
    }

    private fun verifyImages(memberId: Long?, installationId: String, imagePaths: List<String>?) {
        if (imagePaths.isNullOrEmpty()) return
        if (imagePaths.size > ChatMessage.MAX_IMAGE_COUNT) throw BusinessException(ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED)
        val owned = uploadedImageService.ownsAllImages(memberId = memberId, paths = imagePaths, purpose = UploadPurpose.FEEDBACK, installationId = installationId)
        if (!owned) throw BusinessException(ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED)
    }

    private fun requireInstallationId(raw: String?): String =
        raw?.let(ApiHeaders::validInstallationId) ?: throw BusinessException(ErrorCode.INVALID_REQUEST)

    companion object {
        const val QUOTA_SCOPE = "chat"
        const val PREVIEW_LENGTH = 80
        private const val MAX_DEVICE_VALUE_LENGTH = 100
        val QUOTA_WINDOW: Duration = Duration.ofDays(1)
        val DEVICE_INFO_KEYS = setOf("os", "osVersion", "appVersion", "buildNumber", "runtimeVersion", "deviceModel", "locale", "lang", "timezone")

        fun sanitize(deviceInfo: Map<String, String>?): Map<String, String>? =
            deviceInfo?.filterKeys { it in DEVICE_INFO_KEYS }?.mapValues { (_, value) -> value.take(MAX_DEVICE_VALUE_LENGTH) }?.takeIf { it.isNotEmpty() }
    }
}
