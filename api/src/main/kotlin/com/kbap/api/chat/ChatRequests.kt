package com.kbap.api.chat

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "채팅 메시지 보내기")
data class ChatSendRequest(
    @field:Schema(description = "본문 1~2000자. 공백만 보내면 400 FEEDBACK-001", example = "스캔 결과가 이상해요", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String? = null,
    @field:Schema(
        description = "첨부 사진 경로(최대 3장) — 문의와 같은 업로드(purpose=FEEDBACK)로 올린 본인 사진만. 게스트는 업로드 경로가 없어 값을 보내면 400 FEEDBACK-002",
        nullable = true,
    )
    val imagePaths: List<String>? = null,
    @field:Schema(description = "기기 정보(선택, 문의와 같은 키 — os·osVersion·appVersion·buildNumber·runtimeVersion·deviceModel·locale·lang·timezone)", nullable = true)
    val deviceInfo: Map<String, String>? = null,
)

@Schema(description = "읽음 처리 — 이 id 까지 읽었다")
data class ChatReadRequest(
    @field:Schema(description = "화면에 보인 가장 최신 메시지 id", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
    val lastMessageId: Long? = null,
)
