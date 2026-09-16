package com.kbap.api.report

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

@Tag(name = "신고", description = "콘텐츠 신고 접수 API — 이번 버전의 신고 대상은 리뷰(REVIEW)뿐이다")
@SecurityRequirement(name = "bearerAuth")
interface ReportApi {
    @Operation(
        summary = "신고 접수(회원·게스트)",
        description = """
            대상 콘텐츠(targetType + targetId)를 사유와 함께 신고한다.
            인증은 선택이다 — 액세스 토큰이 있으면 회원 신고, 없으면 게스트 신고다. 토큰이 있으나 위조·만료면 401 이며 게스트로 전환되지 않는다.
            `X-Installation-Id` 헤더는 **회원·게스트 모두 필수**다(누락 시 REPORT-004). 회원 신고는 memberId 와 설치 ID 를 함께 저장한다.
            **재신고를 허용한다** — 같은 사람이 같은 대상을 여러 번 신고해도 모두 접수되고 중복 오류를 주지 않는다
            (YouTube·Instagram·TikTok 과 같은 플랫폼 관례).
            게스트 신고는 계정에 귀속되지 않는다 — 나중에 로그인해도 과거 게스트 신고가 회원 신고로 바뀌지 않는다.
            자기 콘텐츠 신고 차단(REPORT-001)은 회원만 적용된다(게스트는 소유 리뷰가 없음).
            **신고는 목록을 바꾸지 않는다** — 접수한 리뷰도 신고자에게 그대로 보인다. 숨김은 회원 차단에만 적용된다.
            취소 기능은 없다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "접수 성공"),
            ApiResponse(
                responseCode = "400",
                description = "필수 누락·미정의 enum·상세 500자 초과, 자기 콘텐츠 신고(REPORT-001), X-Installation-Id 헤더 없음(REPORT-004), 헤더 형식 위반(COMMON-002)",
            ),
            ApiResponse(responseCode = "401", description = "Authorization 헤더가 있으나 형식 오류·위조·만료(게스트는 헤더 자체가 없어야 한다)"),
            ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 대상(REPORT-003)"),
        ],
    )
    @ApiErrors(
        ErrorCode.REPORT_SELF_TARGET,
        ErrorCode.REPORT_TARGET_NOT_FOUND,
        ErrorCode.REPORT_INSTALLATION_ID_REQUIRED,
    )
    fun create(
        memberId: Long?,
        authorization: String?,
        @Parameter(
            `in` = ParameterIn.HEADER,
            name = "X-Installation-Id",
            description = "앱 설치 UUID. 회원·게스트 모두 필수 — 신고 접수 기기를 기록해 어드민이 같은 기기의 신고를 식별한다",
            required = true,
        )
        installationId: String?,
        request: ReportCreateRequest,
    ): ResponseEntity<BaseResponse<Unit>>
}
