package com.kbap.api.chat

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "운영진 채팅", description = "사용자(로그인 회원 또는 게스트 기기)당 대화방 하나(S-971). 게스트는 X-Installation-Id 필수, 로그인하면 그 기기의 게스트 대화가 계정으로 이어진다")
interface ChatApi {
    @Operation(
        summary = "내 대화 메시지(최신부터 위로 더 불러오기)",
        description = """
            최신 → 과거 순으로 limit(1~50, 기본 30)건. 더 과거는 nextCursor 를 before 로 넘긴다.
            지난 문의·답변도 같은 목록에 섞여 오고 legacyFeedbackId 로 구분한다. 방이 없으면 conversationId=null·빈 목록(기본 인사는 앱이 화면에만 띄운다).
            조회만으로는 읽음 처리하지 않는다(POST /api/chat/read).
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST)
    fun getMessages(
        memberId: Long?,
        @Parameter(description = "기기 식별자(게스트 필수, 로그인도 보내면 기기 대화 이어받기에 쓴다)") installationId: String?,
        @Parameter(description = "이 id 보다 과거만") before: Long?,
        @Parameter(description = "1~50, 기본 30") limit: Int,
    ): ResponseEntity<BaseResponse<ChatMessagePage>>

    @Operation(
        summary = "메시지 보내기",
        description = """
            방이 없으면 만든다. 기기당 24시간 100건(kbap.chat.daily-limit), 넘으면 429 CHAT-001.
            보내면 운영진에게 텔레그램 알림이 간다(방당 1분에 한 번으로 묶음). 201 + 저장된 메시지.
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.FEEDBACK_CONTENT_INVALID, ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED, ErrorCode.CHAT_RATE_LIMITED)
    fun sendMessage(memberId: Long?, installationId: String?, request: ChatSendRequest): ResponseEntity<BaseResponse<ChatMessageView>>

    @Operation(
        summary = "읽음 처리",
        description = "화면이 열려 있고 맨 아래가 보일 때 그 시점 최신 메시지 id 로 부른다. 줄어드는 값은 무시한다(단조 증가). 방이 없으면 아무것도 하지 않는다.",
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST)
    fun markRead(memberId: Long?, installationId: String?, request: ChatReadRequest): ResponseEntity<BaseResponse<Unit>>

    @Operation(summary = "안읽음 수(플로팅 버튼 빨간 점·미리보기)", description = "읽지 않은 운영진 메시지 수와 마지막 운영진 메시지 앞 80자. 방이 없으면 0·null.")
    @ApiErrors(ErrorCode.INVALID_REQUEST)
    fun getUnread(memberId: Long?, installationId: String?): ResponseEntity<BaseResponse<ChatUnread>>
}
