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
            관리자 계정(아이디/비밀번호)으로 액세스 토큰 하나를 발급한다(`expiresIn` 초, 기본 8h). 갱신 토큰은 없다 — 만료되면 다시 로그인한다.
            발급된 액세스 토큰은 `/api/admin/**` 전용이다. 회원 API 에 쓰면 401(AUTH-003).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급 성공"),
            ApiResponse(responseCode = "400", description = "id/password 누락(COMMON-002)", content = [Content(schema = Schema(implementation = BaseResponse::class))]),
            ApiResponse(responseCode = "401", description = "아이디/비밀번호 불일치(AUTH-009)"),
        ],
    )
    fun login(request: AdminLoginRequest): ResponseEntity<BaseResponse<AdminTokenResponse>>
}
