package com.kbap.api.feedback

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "문의", description = "버그·의견·아이디어 문의 접수와 내 문의 조회 — 게스트도 쓸 수 있다")
@SecurityRequirement(name = "bearerAuth")
interface FeedbackApi {
    @Operation(
        summary = "문의 제출(회원·게스트)",
        description = """
            제목 없이 본문만 보낸다. 인증은 선택이고 `X-Installation-Id` 헤더는 **회원·게스트 모두 필수**다(누락·형식 위반 400 COMMON-002).
            서버가 memberId·installationId·userAgent·접수 시각을 함께 저장한다.
            사진은 최대 3장이며 `/api/images/upload-url`·`/api/images/complete` 로 purpose=FEEDBACK 업로드한 사진만 첨부할 수 있다.
            **게스트도 사진을 붙일 수 있다** — 두 업로드 엔드포인트는 purpose=FEEDBACK + `X-Installation-Id` 이면 토큰 없이 받는다(기기당 하루 10건).
            소유 검증은 회원이면 member id, 게스트면 같은 설치 ID 업로드까지 인정하며 어긋나면 400(FEEDBACK-002)이다.
            같은 기기에서 하루 20건을 넘기면 429(FEEDBACK-003)다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "접수 성공"),
            ApiResponse(responseCode = "400", description = "본문 길이 위반(FEEDBACK-001)·사진 위반(FEEDBACK-002)·설치 ID 헤더 문제(COMMON-002)"),
            ApiResponse(responseCode = "429", description = "기기당 일일 한도 초과(FEEDBACK-003)"),
        ],
    )
    @ApiErrors(
        ErrorCode.FEEDBACK_CONTENT_INVALID,
        ErrorCode.FEEDBACK_IMAGE_NOT_VERIFIED,
        ErrorCode.FEEDBACK_RATE_LIMITED,
    )
    fun create(
        memberId: Long?,
        @Parameter(
            `in` = ParameterIn.HEADER,
            name = "X-Installation-Id",
            description = "앱 설치 UUID. 회원·게스트 모두 필수 — 내 문의 조회가 이 값으로도 매칭된다",
            required = true,
        )
        installationId: String?,
        userAgent: String?,
        request: FeedbackCreateRequest,
    ): ResponseEntity<BaseResponse<FeedbackCreateResponse>>

    @Operation(
        summary = "내 문의 목록(회원·게스트)",
        description = """
            이 기기(설치 ID)로 보냈거나 로그인한 회원이 보낸 문의를 최신순으로 내려준다 — **설치 ID 또는 회원 id 매칭**이라
            게스트로 보낸 문의가 가입 후에도 같은 기기에서 보인다. 답변은 오래된 순이며 답변자 정보는 내려주지 않는다.
            커서 페이지네이션이며 `nextCursor` 가 null 이면 마지막 페이지다.
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    fun getMine(
        memberId: Long?,
        @Parameter(
            `in` = ParameterIn.HEADER,
            name = "X-Installation-Id",
            description = "앱 설치 UUID. 필수",
            required = true,
        )
        installationId: String?,
        @Parameter(description = "이전 응답의 nextCursor", example = "42") cursor: Long?,
        @Parameter(description = "페이지 크기(기본 20)", example = "20") size: Int?,
    ): ResponseEntity<BaseResponse<MyFeedbackPageResponse>>

    @Operation(
        summary = "내 문의 상세(회원·게스트)",
        description = """
            문의 한 건을 목록 아이템과 같은 모양으로 내려준다 — 알림 딥링크·상세 화면용이다.
            소유 조건은 목록과 같은 **설치 ID 또는 회원 id 매칭**이며, 남의 문의는 존재 여부를 숨기고 404(FEEDBACK-004)로 답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "없거나 내 문의가 아님(FEEDBACK-004)"),
        ],
    )
    @ApiErrors(ErrorCode.FEEDBACK_NOT_FOUND)
    fun getOne(
        memberId: Long?,
        @Parameter(
            `in` = ParameterIn.HEADER,
            name = "X-Installation-Id",
            description = "앱 설치 UUID. 필수",
            required = true,
        )
        installationId: String?,
        @Parameter(description = "문의 id", example = "12") id: Long,
    ): ResponseEntity<BaseResponse<MyFeedbackItemResponse>>
}
