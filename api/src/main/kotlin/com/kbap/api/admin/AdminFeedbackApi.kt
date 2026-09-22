package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import com.kbap.api.feedback.FeedbackReplyCreateRequest
import com.kbap.api.feedback.FeedbackStatusUpdateRequest
import org.springframework.http.ResponseEntity

@Tag(name = "어드민 - 문의", description = "사용자 문의 목록·상세·답변·상태 변경")
@SecurityRequirement(name = "bearerAuth")
interface AdminFeedbackApi {
    @Operation(
        summary = "문의 목록",
        description = """
            최신순으로 내려준다. `status` 를 생략하면 **OPEN 만** 보여주고 `ALL` 이면 전부 본다.
            `reporterKey` 는 신고 계약과 같은 규칙이라 같은 기기에서 온 문의·신고를 같은 키로 묶어 볼 수 있다.
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    fun getFeedbacks(
        @Parameter(description = "OPEN·ANSWERED·CLOSED·ALL. 생략 시 OPEN", example = "OPEN") status: String?,
        @Parameter(description = "0부터", example = "0") page: Int?,
        @Parameter(description = "기본 20", example = "20") size: Int?,
    ): ResponseEntity<BaseResponse<AdminFeedbackPageResponse>>

    @Operation(
        summary = "문의 상세",
        description = "본문·사진·기기 정보 전체와 서버가 채운 메타(userAgent·접수 시각), 답변 목록을 함께 준다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "없는 문의(FEEDBACK-004)"),
        ],
    )
    @ApiErrors(ErrorCode.FEEDBACK_NOT_FOUND)
    fun getFeedback(
        @Parameter(description = "문의 id", required = true, example = "12") id: Long,
    ): ResponseEntity<BaseResponse<AdminFeedbackDetailResponse>>

    @Operation(
        summary = "문의 답변",
        description = """
            답변을 달면 문의 상태가 OPEN 이었을 때만 ANSWERED 로 바뀐다(이미 ANSWERED 면 그대로).
            **종료(CLOSED)된 문의에는 답변할 수 없다** — 409(FEEDBACK-005).
            답변자 계정은 어드민 화면에만 보이고 앱 응답에는 내려가지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "답변 작성"),
            ApiResponse(responseCode = "409", description = "종료된 문의(FEEDBACK-005)"),
        ],
    )
    @ApiErrors(ErrorCode.FEEDBACK_NOT_FOUND, ErrorCode.FEEDBACK_CLOSED, ErrorCode.FEEDBACK_CONTENT_INVALID)
    fun reply(
        @Parameter(description = "문의 id", required = true, example = "12") id: Long,
        adminAccountId: Long,
        request: FeedbackReplyCreateRequest,
    ): ResponseEntity<BaseResponse<AdminFeedbackReplyResponse>>

    @Operation(summary = "문의 상태 변경", description = "OPEN·ANSWERED·CLOSED 중 하나로 직접 바꾼다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "변경 성공")])
    @ApiErrors(ErrorCode.FEEDBACK_NOT_FOUND)
    fun changeStatus(
        @Parameter(description = "문의 id", required = true, example = "12") id: Long,
        request: FeedbackStatusUpdateRequest,
    ): ResponseEntity<BaseResponse<AdminFeedbackStatusResponse>>
}
