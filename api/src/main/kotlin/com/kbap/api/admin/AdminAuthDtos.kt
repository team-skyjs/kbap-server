package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class AdminLoginRequest(
    @field:NotBlank(message = "id 는 필수입니다")
    @field:Schema(description = "관리자 로그인 아이디", example = "ops")
    val id: String?,
    @field:NotBlank(message = "password 는 필수입니다")
    @field:Schema(description = "비밀번호", example = "********")
    val password: String?,
)

data class AdminRefreshRequest(
    @field:NotBlank(message = "refreshToken 은 필수입니다")
    val refreshToken: String?,
)

data class AdminLogoutRequest(
    val refreshToken: String? = null,
)

@Schema(description = "관리자 자격 응답")
data class AdminTokenResponse(
    @field:Schema(description = "관리자 액세스 토큰(Bearer)")
    val accessToken: String,
    @field:Schema(description = "관리자 갱신 토큰(1회용 — 갱신 시 회전)")
    val refreshToken: String,
    @field:Schema(description = "액세스 토큰 수명(초)", example = "3600")
    val expiresIn: Long,
) {
    companion object {
        fun from(tokens: AdminTokens): AdminTokenResponse =
            AdminTokenResponse(tokens.accessToken, tokens.refreshToken, tokens.expiresIn)
    }
}
