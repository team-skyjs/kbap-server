package com.kbap.api.chat

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/chat")
class ChatController(
    private val chatService: ChatService,
) : ChatApi {
    @GetMapping("/messages")
    override fun getMessages(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "30") limit: Int,
    ): ResponseEntity<BaseResponse<ChatMessagePage>> {
        if (limit !in 1..MAX_LIMIT) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ResponseEntity.ok(BaseResponse.ok(chatService.getMessagePage(memberId, installationId, before, limit)))
    }

    @PostMapping("/messages")
    override fun sendMessage(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @RequestBody request: ChatSendRequest,
    ): ResponseEntity<BaseResponse<ChatMessageView>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(BaseResponse.ok(chatService.sendMessage(memberId, installationId, request.content, request.imagePaths, request.deviceInfo)))

    @PostMapping("/read")
    override fun markRead(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @RequestBody request: ChatReadRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        chatService.markRead(memberId, installationId, request.lastMessageId ?: throw BusinessException(ErrorCode.INVALID_REQUEST))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @GetMapping("/unread")
    override fun getUnread(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
    ): ResponseEntity<BaseResponse<ChatUnread>> =
        ResponseEntity.ok(BaseResponse.ok(chatService.getUnread(memberId, installationId)))

    private companion object {
        const val MAX_LIMIT = 50
    }
}
