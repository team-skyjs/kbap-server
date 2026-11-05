package com.kbap.api.chat

import com.kbap.common.domain.chat.ChatMessageJpaRepository
import com.kbap.common.domain.chat.model.ChatAuthorRole
import com.kbap.common.domain.chat.model.ChatMessage
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId

@Component
class ChatMessageViews(
    private val messages: ChatMessageJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    fun page(conversationId: Long, before: Long?, limit: Int): ChatMessagePage {
        val rows = messages.findPage(conversationId, before, PageRequest.of(0, limit + 1))
        val items = rows.take(limit)
        val hasMore = rows.size > limit
        return ChatMessagePage(
            conversationId = conversationId,
            items = items.map(::of),
            nextCursor = items.lastOrNull()?.id?.toString()?.takeIf { hasMore },
            hasMore = hasMore,
        )
    }

    fun of(message: ChatMessage) = ChatMessageView(
        id = message.id,
        authorRole = message.authorRole,
        content = message.content,
        imageUrls = message.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
        createdAt = message.createdAt.atZone(ZoneId.systemDefault()).toInstant(),
        legacyFeedbackId = message.legacyFeedbackId,
    )
}

@Schema(description = "대화 메시지 한 페이지 — 최신부터 과거 순")
data class ChatMessagePage(
    @field:Schema(description = "대화방 id — 아직 방이 없으면 null(앱은 기본 인사를 화면에만 띄운다)", nullable = true)
    val conversationId: Long?,
    @field:Schema(description = "최신 → 과거 순")
    val items: List<ChatMessageView>,
    @field:Schema(description = "더 과거를 불러올 때 다음 before 값 — 없으면 null", nullable = true)
    val nextCursor: String?,
    val hasMore: Boolean,
)

@Schema(description = "채팅 메시지")
data class ChatMessageView(
    val id: Long,
    @field:Schema(description = "USER(사용자) 또는 OPERATOR(운영진 — K-Bap 팀)")
    val authorRole: ChatAuthorRole,
    @field:Schema(description = "원문 그대로(번역 없음)")
    val content: String,
    @field:Schema(description = "사용자 첨부 사진 URL — 운영진 메시지는 빈 배열")
    val imageUrls: List<String>,
    @field:Schema(description = "작성 시각(UTC, ISO-8601 Z 접미 — 예: 2026-11-05T09:15:59.154Z)")
    val createdAt: Instant,
    @field:Schema(description = "지난 문의에서 옮겨 온 메시지면 그 문의 id, 채팅에서 보낸 메시지면 null", nullable = true)
    val legacyFeedbackId: Long?,
)

@Schema(description = "앱 채팅 안읽음(플로팅 버튼 빨간 점·미리보기)")
data class ChatUnread(
    @field:Schema(description = "사용자가 아직 읽지 않은 운영진 메시지 수")
    val unreadCount: Long,
    @field:Schema(description = "마지막 운영진 메시지(앞 80자) — 없으면 null", nullable = true)
    val lastOperatorMessage: Preview?,
) {
    data class Preview(val content: String, val createdAt: Instant)
}
