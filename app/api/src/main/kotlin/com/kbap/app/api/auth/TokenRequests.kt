package com.kbap.app.api.auth

import com.kbap.application.auth.RefreshResult
import jakarta.validation.constraints.NotBlank

data class RefreshRequest(
    @field:NotBlank(message = "refreshToken 은 필수입니다")
    val refreshToken: String?,
)

data class LogoutRequest(
    val refreshToken: String? = null,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
) {
    companion object {
        fun from(result: RefreshResult): TokenResponse =
            TokenResponse(accessToken = result.accessToken, refreshToken = result.refreshToken)
    }
}
