package com.kbap.api.chat

import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.alert.OperatorAlertSender
import com.kbap.common.port.push.PushHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration

@Component
class ChatNotificationListener(
    private val pushHandler: PushHandler,
    private val alertSender: OperatorAlertSender,
    private val members: MemberJpaRepository,
    private val redis: StringRedisTemplate,
    @Value("\${kbap.chat.admin-base-url:}") private val adminBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onOperatorMessage(event: ChatOperatorMessageSent) {
        try {
            val result = pushHandler.send(
                PushRequest(
                    type = NotificationType.OPERATOR_CHAT,
                    memberIds = listOfNotNull(event.memberId),
                    guestInstallationIds = listOfNotNull(event.guestInstallationId),
                    args = mapOf("message" to event.content.take(PUSH_PREVIEW_LENGTH)),
                    data = mapOf("conversationId" to event.conversationId),
                ),
            )
            log.info("운영진 채팅 푸시 conversationId={} sent={} failed={}", event.conversationId, result.sent, result.failed)
        } catch (e: Exception) {
            log.error("운영진 채팅 푸시 실패 conversationId={}", event.conversationId, e)
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onUserMessage(event: ChatUserMessageSent) {
        val throttled = runCatching { redis.opsForValue().setIfAbsent(ALERT_PREFIX + event.conversationId, "1", ALERT_WINDOW) != true }.getOrDefault(false)
        if (throttled) return
        val text = alertText(event)
        repeat(ALERT_ATTEMPTS) { attempt ->
            try {
                alertSender.send(text)
                return
            } catch (e: Exception) {
                log.warn("운영진 채팅 텔레그램 알림 실패 conversationId={} attempt={}", event.conversationId, attempt + 1, e)
            }
        }
    }

    private fun alertText(event: ChatUserMessageSent): String {
        val who = event.memberId?.let { id -> members.findById(id).orElse(null)?.profile?.nickname?.let { "$it(#$id)" } ?: "회원 #$id" } ?: "게스트"
        val photos = if (event.imageCount > 0) " (사진 ${event.imageCount}장)" else ""
        val link = adminBaseUrl.trimEnd('/').takeIf { it.isNotEmpty() }?.let { "\n$it/chats/${event.conversationId}" }.orEmpty()
        return "[K-Bap 채팅] $who : ${event.content.take(ALERT_PREVIEW_LENGTH)}$photos$link"
    }

    companion object {
        const val ALERT_PREFIX = "chat:alert:"
        private val ALERT_WINDOW = Duration.ofMinutes(1)
        private const val ALERT_ATTEMPTS = 2
        private const val ALERT_PREVIEW_LENGTH = 100
        private const val PUSH_PREVIEW_LENGTH = 100
    }
}
