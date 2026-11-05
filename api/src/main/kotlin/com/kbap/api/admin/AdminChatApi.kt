package com.kbap.api.admin

import com.kbap.api.chat.ChatMessageView
import com.kbap.api.chat.ChatReadRequest
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 운영진 채팅", description = "ADMIN 전용(S-971). 쓰기(답장·방 열기)는 X-Target-Environment dev|prod 필수 — 서버 환경과 다르면 409 CHAT-003")
@SecurityRequirement(name = "bearerAuth")
interface AdminChatApi {
    @Operation(summary = "대화 목록", description = "안읽음(사용자 메시지) 있는 방 먼저, 그다음 마지막 메시지 최신 순. cursor 는 받은 nextCursor 를 그대로 넘긴다(불투명 문자열). country 는 회원 프로필 국가 또는 게스트 기기 locale 의 지역, language 는 마지막 사용자 메시지의 기기 lang(없으면 locale 앞부분).")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_CURSOR)
    fun getConversations(@Parameter(description = "이전 응답의 nextCursor") cursor: String?, @Parameter(description = "1~50, 기본 30") size: Int): ResponseEntity<BaseResponse<AdminChatConversationPage>>

    @Operation(summary = "사이드바 안읽음 배지", description = "운영진이 읽지 않은 사용자 메시지가 있는 방 수와 메시지 수")
    fun getUnread(): ResponseEntity<BaseResponse<AdminChatUnread>>

    @Operation(summary = "지난 문의 → 대화방", description = "지난 문의(/feedbacks/{id}) 링크를 그 문의가 옮겨진 대화방 id 로 바꾼다. 없으면 404 CHAT-002.")
    @ApiErrors(ErrorCode.CHAT_CONVERSATION_NOT_FOUND)
    fun getByFeedback(@Parameter(description = "지난 문의 id") feedbackId: Long): ResponseEntity<BaseResponse<AdminChatOpenResult>>

    @Operation(summary = "대화 상세", description = "메시지(최신 → 과거, before·limit)와 사용자 정보 패널.")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.CHAT_CONVERSATION_NOT_FOUND)
    fun getConversation(conversationId: Long, @Parameter(description = "이 id 보다 과거만") before: Long?, @Parameter(description = "1~50, 기본 30") limit: Int): ResponseEntity<BaseResponse<AdminChatConversationDetail>>

    @Operation(summary = "답장(먼저 보내기 포함)", description = "글만(1차). 보내면 그 사용자 기기 전부에 푸시(OPERATOR_CHAT, 앱 내 토글 없음)가 간다 — 게스트 방은 그 기기.")
    @ApiErrors(ErrorCode.FEEDBACK_CONTENT_INVALID, ErrorCode.CHAT_CONVERSATION_NOT_FOUND, ErrorCode.CHAT_ENVIRONMENT_MISMATCH)
    fun reply(conversationId: Long, @Parameter(description = "dev 또는 prod") target: String, adminAccountId: Long, request: AdminChatReplyRequest): ResponseEntity<BaseResponse<ChatMessageView>>

    @Operation(summary = "운영진 읽음 처리", description = "이 id 까지 읽음 — 안읽음 배지를 내린다. 줄어드는 값은 무시.")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.CHAT_CONVERSATION_NOT_FOUND)
    fun markRead(conversationId: Long, request: ChatReadRequest): ResponseEntity<BaseResponse<Unit>>

    @Operation(summary = "대화방 열기", description = "회원 id 또는 게스트 기기 id 로 방을 연다. 이미 있으면 같은 id(created=false), 없으면 만든다(created=true). 먼저 보내기는 이어서 답장을 부른다.")
    @ApiErrors(ErrorCode.CHAT_TARGET_INVALID, ErrorCode.CHAT_MEMBER_NOT_FOUND, ErrorCode.CHAT_ENVIRONMENT_MISMATCH)
    fun open(@Parameter(description = "dev 또는 prod") target: String, request: AdminChatOpenRequest): ResponseEntity<BaseResponse<AdminChatOpenResult>>
}
