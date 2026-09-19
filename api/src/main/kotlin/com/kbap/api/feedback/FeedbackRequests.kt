package com.kbap.api.feedback

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "문의 제출 요청 — 제목은 없다")
data class FeedbackCreateRequest(
    @field:Schema(
        description = "문의 본문. 1~2000자이며 공백만 보내면 400 FEEDBACK-001",
        example = "홈에서 스캔 버튼이 두 번 눌려요",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val content: String? = null,

    @field:Schema(
        description = "첨부 사진 경로(최대 3장). /api/images 로 purpose=FEEDBACK 업로드한 본인 사진만 허용한다. " +
            "게스트는 업로드 경로가 없어 값을 보내면 400 FEEDBACK-002",
        nullable = true,
    )
    val imagePaths: List<String>? = null,

    @field:Schema(
        description = "클라이언트가 자동 수집한 기기 정보(선택). os·osVersion·appVersion·buildNumber·runtimeVersion·" +
            "deviceModel·locale·lang·timezone 중 있는 키만 보내면 되며 그대로 저장된다. 유저 입력 아님",
        nullable = true,
    )
    val deviceInfo: Map<String, String>? = null,
)

@Schema(description = "문의 답변 작성 요청")
data class FeedbackReplyCreateRequest(
    @field:Schema(description = "답변 본문. 1~2000자", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String? = null,
)

@Schema(description = "문의 상태 변경 요청")
data class FeedbackStatusUpdateRequest(
    @field:Schema(description = "바꿀 상태", example = "CLOSED", requiredMode = Schema.RequiredMode.REQUIRED)
    val status: String? = null,
)
