package com.kbap.api.admin

import com.kbap.api.chat.ChatMessageView
import com.kbap.api.chat.ChatReadRequest
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/chats", version = "1.0+")
class AdminChatController(
    private val adminChatService: AdminChatService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminChatApi {
    @GetMapping
    override fun getConversations(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") size: Int,
    ): ResponseEntity<BaseResponse<AdminChatConversationPage>> {
        if (size !in 1..MAX_SIZE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ResponseEntity.ok(BaseResponse.ok(adminChatService.getConversationPage(cursor, size)))
    }

    @GetMapping("/unread")
    override fun getUnread(): ResponseEntity<BaseResponse<AdminChatUnread>> =
        ResponseEntity.ok(BaseResponse.ok(adminChatService.getUnreadTotals()))

    @GetMapping("/by-feedback/{feedbackId}")
    override fun getByFeedback(@PathVariable feedbackId: Long): ResponseEntity<BaseResponse<AdminChatOpenResult>> =
        ResponseEntity.ok(BaseResponse.ok(AdminChatOpenResult(adminChatService.getConversationIdByFeedback(feedbackId), created = false)))

    @GetMapping("/{conversationId}/messages")
    override fun getConversation(
        @PathVariable conversationId: Long,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "30") limit: Int,
    ): ResponseEntity<BaseResponse<AdminChatConversationDetail>> {
        if (limit !in 1..MAX_SIZE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ResponseEntity.ok(BaseResponse.ok(adminChatService.getConversation(conversationId, before, limit)))
    }

    @PostMapping("/{conversationId}/messages")
    override fun reply(
        @PathVariable conversationId: Long,
        @RequestHeader("X-Target-Environment") target: String,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
        @RequestBody request: AdminChatReplyRequest,
    ): ResponseEntity<BaseResponse<ChatMessageView>> {
        requireEnvironment(target)
        return ResponseEntity.ok(BaseResponse.ok(adminChatService.reply(conversationId, adminAccountId, request.content)))
    }

    @PostMapping("/{conversationId}/read")
    override fun markRead(
        @PathVariable conversationId: Long,
        @RequestBody request: ChatReadRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        adminChatService.markRead(conversationId, request.lastMessageId ?: throw BusinessException(ErrorCode.INVALID_REQUEST))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @PostMapping
    override fun open(
        @RequestHeader("X-Target-Environment") target: String,
        @RequestBody request: AdminChatOpenRequest,
    ): ResponseEntity<BaseResponse<AdminChatOpenResult>> {
        requireEnvironment(target)
        return ResponseEntity.ok(BaseResponse.ok(adminChatService.open(request.memberId, request.installationId)))
    }

    private fun requireEnvironment(target: String) {
        if (target != environment) throw BusinessException(ErrorCode.CHAT_ENVIRONMENT_MISMATCH)
    }

    private companion object {
        const val MAX_SIZE = 50
    }
}

@Schema(description = "운영진 답장 — 글만(1차)")
data class AdminChatReplyRequest(
    @field:Schema(description = "1~2000자", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String? = null,
)

@Schema(description = "대화방 열기 — memberId 또는 installationId 중 하나만")
data class AdminChatOpenRequest(
    val memberId: Long? = null,
    @field:Schema(description = "게스트 기기 id(X-Installation-Id 값)")
    val installationId: String? = null,
)
