package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 인증", description = "관리자 SPA 용 자격 발급 — 회원 자격과 구분되며 서로 교차 사용할 수 없다")
interface AdminAuthApi {
    @Operation(
        summary = "관리자 로그인",
        description = """
            관리자 계정(아이디/비밀번호)으로 액세스·갱신 토큰을 발급한다.

            - 연속 5회 실패 시 15분 잠금(AUTH-010) — 잠금 중에는 올바른 비밀번호도 거절되며 잠금 시간은 연장되지 않는다.
            - 발급된 액세스 토큰은 `/api/admin/**` 전용이다. 회원 API 에 쓰면 401(AUTH-003).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급 성공"),
            ApiResponse(responseCode = "400", description = "id/password 누락(COMMON-002)", content = [Content(schema = Schema(implementation = BaseResponse::class))]),
            ApiResponse(responseCode = "401", description = "아이디/비밀번호 불일치(AUTH-009)"),
            ApiResponse(responseCode = "403", description = "로그인 잠금 중(AUTH-010)"),
        ],
    )
    fun login(request: AdminLoginRequest): ResponseEntity<BaseResponse<AdminTokenResponse>>

    @Operation(
        summary = "관리자 자격 갱신",
        description = "갱신 토큰으로 새 액세스·갱신 토큰을 받는다. 갱신 토큰은 1회용(회전) — 사용된 토큰을 다시 쓰면 401(AUTH-005). 회원용 갱신 토큰은 거절된다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "갱신 성공"),
            ApiResponse(responseCode = "401", description = "유효하지 않거나 이미 사용된 갱신 토큰(AUTH-005), 만료(AUTH-006)"),
        ],
    )
    fun refresh(request: AdminRefreshRequest): ResponseEntity<BaseResponse<AdminTokenResponse>>

    @Operation(summary = "관리자 로그아웃", description = "갱신 토큰을 폐기한다. 토큰이 비었거나 파싱할 수 없어도 200.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "폐기 완료")])
    fun logout(request: AdminLogoutRequest?): ResponseEntity<BaseResponse<Unit>>
}
