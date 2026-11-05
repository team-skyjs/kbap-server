package com.kbap.api.admin

import com.kbap.api.chat.ChatMessagePage
import com.kbap.api.chat.ChatMessageView
import com.kbap.api.chat.ChatMessageViews
import com.kbap.api.chat.ChatOperatorMessageSent
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.chat.ChatConversationJpaRepository
import com.kbap.common.domain.chat.ChatMessageJpaRepository
import com.kbap.common.domain.chat.model.ChatAuthorRole
import com.kbap.common.domain.chat.model.ChatMessage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneId

@Service
class AdminChatService(
    private val conversations: ChatConversationJpaRepository,
    private val messages: ChatMessageJpaRepository,
    private val members: MemberJpaRepository,
    private val views: ChatMessageViews,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    fun getConversationPage(cursor: String?, size: Int): AdminChatConversationPage {
        val offset = cursor?.let { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: 0
        val rows = conversations.findAdminRows(size + 1, offset)
        val page = rows.take(size)
        val byId = conversations.findAllById(page.map { it.conversationId }).associateBy { it.id }
        val membersById = members.findAllById(page.mapNotNull { it.memberId }).associateBy { it.id }
        val latest = page.takeIf { it.isNotEmpty() }?.let { messages.findLatestByConversationIds(it.map { row -> row.conversationId }).associateBy { m -> m.conversationId } }.orEmpty()
        return AdminChatConversationPage(
            items = page.mapNotNull { row ->
                val conversation = byId[row.conversationId] ?: return@mapNotNull null
                val member = row.memberId?.let { membersById[it] }
                AdminChatConversationItem(
                    conversationId = conversation.id,
                    memberId = conversation.memberId,
                    installationId = conversation.guestInstallationId,
                    nickname = member?.profile?.nickname,
                    country = countryOf(member, conversation.lastDeviceInfo),
                    language = languageOf(conversation.lastDeviceInfo),
                    unreadCount = row.unreadCount,
                    lastMessage = latest[conversation.id]?.let { AdminChatLastMessage(it.authorRole, it.content.take(PREVIEW_LENGTH), it.createdAt.atZone(ZoneId.systemDefault()).toInstant()) },
                )
            },
            nextCursor = (offset + size).toString().takeIf { rows.size > size },
            hasMore = rows.size > size,
        )
    }

    @Transactional(readOnly = true)
    fun getConversation(conversationId: Long, before: Long?, limit: Int): AdminChatConversationDetail {
        val conversation = conversations.findById(conversationId).orElse(null) ?: throw BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND)
        val member = conversation.memberId?.let { members.findById(it).orElse(null) }
        return AdminChatConversationDetail(
            messages = views.page(conversation.id, before, limit),
            user = AdminChatUser(
                memberId = conversation.memberId,
                installationId = conversation.guestInstallationId,
                nickname = member?.profile?.nickname,
                country = countryOf(member, conversation.lastDeviceInfo),
                language = languageOf(conversation.lastDeviceInfo),
                deviceInfo = conversation.lastDeviceInfo.orEmpty(),
                memberCreatedAt = member?.createdAt?.atZone(ZoneId.systemDefault())?.toInstant(),
            ),
        )
    }

    fun reply(conversationId: Long, adminAccountId: Long, content: String?): ChatMessageView {
        val body = content?.trim().orEmpty()
        if (body.isEmpty() || body.length > ChatMessage.MAX_CONTENT_LENGTH) throw BusinessException(ErrorCode.FEEDBACK_CONTENT_INVALID)
        val (message, conversation) = transaction.execute {
            val conversation = conversations.findByIdForUpdate(conversationId) ?: throw BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND)
            val message = messages.saveAndFlush(ChatMessage.fromOperator(conversation.id, adminAccountId, body))
            conversation.recordOperatorMessage(message)
            message to conversation
        }!!
        eventPublisher.publishEvent(ChatOperatorMessageSent(conversation.id, message.id, conversation.memberId, conversation.guestInstallationId, message.content))
        return views.of(message)
    }

    fun appendLegacyReply(feedbackId: Long, replyId: Long, adminAccountId: Long, content: String) {
        val anchor = messages.findFirstByLegacyFeedbackIdOrderByIdAsc(feedbackId) ?: return
        val conversation = conversations.findByIdForUpdate(anchor.conversationId) ?: return
        val message = messages.saveAndFlush(ChatMessage.fromOperator(conversation.id, adminAccountId, content, legacyFeedbackId = feedbackId, legacyReplyId = replyId))
        conversation.recordOperatorMessage(message)
    }

    @Transactional
    fun markRead(conversationId: Long, lastMessageId: Long) {
        val conversation = conversations.findByIdForUpdate(conversationId) ?: throw BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND)
        val latest = messages.findFirstByConversationIdOrderByIdDesc(conversation.id)?.id ?: return
        conversation.markOperatorRead(minOf(lastMessageId, latest))
    }

    fun open(memberId: Long?, installationId: String?): AdminChatOpenResult {
        val guest = installationId?.trim()?.takeIf { it.isNotEmpty() }
        if ((memberId == null) == (guest == null)) throw BusinessException(ErrorCode.CHAT_TARGET_INVALID)
        if (guest != null && guest.length > MAX_INSTALLATION_ID_LENGTH) throw BusinessException(ErrorCode.CHAT_TARGET_INVALID)
        if (memberId != null && !members.existsByIdAndMemberStatus(memberId, MemberStatus.ACTIVE)) throw BusinessException(ErrorCode.CHAT_MEMBER_NOT_FOUND)
        return transaction.execute {
            val existingId = if (memberId != null) conversations.findIdByMemberId(memberId) else conversations.findGuestId(guest!!)
            if (existingId != null) return@execute AdminChatOpenResult(existingId, created = false)
            val inserted = if (memberId != null) conversations.insertIgnoreForMember(memberId) else conversations.insertIgnoreForGuest(guest!!)
            val id = (if (memberId != null) conversations.findIdByMemberId(memberId) else conversations.findGuestId(guest!!))
                ?: throw BusinessException(ErrorCode.CHAT_MEMBER_NOT_FOUND)
            AdminChatOpenResult(id, created = inserted > 0)
        }!!
    }

    @Transactional(readOnly = true)
    fun getConversationIdByFeedback(feedbackId: Long): Long =
        messages.findFirstByLegacyFeedbackIdOrderByIdAsc(feedbackId)?.conversationId ?: throw BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND)

    @Transactional(readOnly = true)
    fun getUnreadTotals(): AdminChatUnread = conversations.countOperatorUnread().let { AdminChatUnread(it.conversations, it.messages) }

    companion object {
        const val PREVIEW_LENGTH = 80
        private const val MAX_INSTALLATION_ID_LENGTH = 36

        fun countryOf(member: Member?, deviceInfo: Map<String, String>?): String? =
            member?.profile?.countryCode?.name ?: deviceInfo?.get("locale")?.split('-', '_')?.getOrNull(1)?.uppercase()?.takeIf { it.length == 2 }

        fun languageOf(deviceInfo: Map<String, String>?): String? =
            deviceInfo?.get("lang")?.takeIf { it.isNotBlank() } ?: deviceInfo?.get("locale")?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}

data class AdminChatConversationPage(val items: List<AdminChatConversationItem>, val nextCursor: String?, val hasMore: Boolean)

data class AdminChatConversationItem(
    val conversationId: Long,
    val memberId: Long?,
    val installationId: String?,
    val nickname: String?,
    val country: String?,
    val language: String?,
    val unreadCount: Long,
    val lastMessage: AdminChatLastMessage?,
)

data class AdminChatLastMessage(val authorRole: ChatAuthorRole, val content: String, val createdAt: Instant)

data class AdminChatConversationDetail(val messages: ChatMessagePage, val user: AdminChatUser)

data class AdminChatUser(
    val memberId: Long?,
    val installationId: String?,
    val nickname: String?,
    val country: String?,
    val language: String?,
    val deviceInfo: Map<String, String>,
    val memberCreatedAt: Instant?,
)

data class AdminChatOpenResult(val conversationId: Long, val created: Boolean)

data class AdminChatUnread(val conversations: Long, val messages: Long)
