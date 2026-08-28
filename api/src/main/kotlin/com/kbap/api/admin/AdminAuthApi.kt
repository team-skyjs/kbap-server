package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "관리자 인증", description = "어드민 SPA 전용 — 관리자 계정으로 로그인해 ADMIN 액세스 토큰을 발급받는 API")
interface AdminAuthApi {
    @Operation(
        summary = "관리자 로그인",
        description = """
            관리자 계정(id/password)을 검증하고 ADMIN 역할 액세스 토큰을 발급한다.
            발급된 토큰은 어드민 API 호출 시 `Authorization: Bearer` 헤더로 보낸다.

            - 액세스 토큰 없이 호출하는 유일한 어드민 API 다.
            - 자격 불일치는 아이디 없음/비밀번호 틀림을 구분하지 않고 401(AUTH-009) 하나로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "로그인 성공 — accessToken 반환"),
            ApiResponse(
                responseCode = "400",
                description = "요청 검증 실패(COMMON-002) — id·password 누락",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호 불일치(AUTH-009)"),
        ],
    )
    fun login(
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = AdminLoginRequest::class),
                    examples = [ExampleObject(name = "로그인", value = """{"id": "admin", "password": "secret"}""")],
                ),
            ],
        )
        request: AdminLoginRequest,
    ): ResponseEntity<BaseResponse<AdminLoginResponse>>
}
