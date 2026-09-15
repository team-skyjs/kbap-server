package com.kbap.api.report

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "신고", description = "콘텐츠 신고 접수 API — 이번 버전의 신고 대상은 리뷰(REVIEW)뿐이다")
interface ReportApi {
    @Operation(
        summary = "신고 접수(회원·게스트)",
        description = """
            대상 콘텐츠(targetType + targetId)를 사유와 함께 신고한다.
            인증은 선택이다 — 액세스 토큰이 있으면 회원 신고, 없으면 게스트 신고다. 토큰이 있으나 위조·만료면 401 이며 게스트로 전환되지 않는다.
            `X-Installation-Id` 헤더는 **회원·게스트 모두 필수**다(누락 시 REPORT-004). 회원 신고는 memberId 와 설치 ID 를 함께 저장한다.
            중복 판정은 **회원 키 또는 설치 키** 둘 중 하나라도 걸리면 409(REPORT-002)다 — 같은 회원이 다른 기기에서 다시 신고해도,
            같은 기기의 다른 계정이 같은 대상을 신고해도 한 건으로 처리한다(여기서 기기 = 앱 설치 단위이며 재설치하면 달라진다).
            자기 콘텐츠 신고 차단(REPORT-001)은 회원만 적용된다(게스트는 소유 리뷰가 없음).
            접수 후 신고자 본인의 리뷰 목록에서 해당 리뷰가 제외된다(다른 사용자에게는 그대로 노출). 제외는 회원 신고분과 현재 설치 신고분의 합집합이라,
            같은 설치에서 로그인·로그아웃해도 숨김이 이어진다.
            같은 대상은 신고자당 한 번만 신고할 수 있고 취소 기능은 없다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "접수 성공"),
            ApiResponse(
                responseCode = "400",
                description = "필수 누락·미정의 enum·상세 500자 초과, 자기 콘텐츠 신고(REPORT-001), X-Installation-Id 헤더 없음(REPORT-004), 헤더 형식 위반(COMMON-002)",
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰이 있으나 위조·만료(게스트는 토큰 없이 허용)"),
            ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 대상(REPORT-003)"),
            ApiResponse(responseCode = "409", description = "이 계정 또는 기기에서 이미 신고한 대상(REPORT-002)"),
        ],
    )
    @ApiErrors(
        ErrorCode.REPORT_SELF_TARGET,
        ErrorCode.REPORT_TARGET_NOT_FOUND,
        ErrorCode.REPORT_DUPLICATED,
        ErrorCode.REPORT_INSTALLATION_ID_REQUIRED,
    )
    fun create(
        memberId: Long?,
        @Parameter(
            `in` = ParameterIn.HEADER,
            name = "X-Installation-Id",
            description = "앱 설치 UUID. 회원·게스트 모두 필수 — 중복 판정과 숨김 연속성에 쓰인다",
            required = true,
        )
        installationId: String?,
        request: ReportCreateRequest,
    ): ResponseEntity<BaseResponse<Unit>>
}
