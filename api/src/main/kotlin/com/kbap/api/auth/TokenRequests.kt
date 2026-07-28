package com.kbap.api.auth

import jakarta.validation.constraints.NotBlank

data class RefreshRequest(
    @field:NotBlank(message = "refreshToken 은 필수입니다")
    val refreshToken: String?,
)

data class LogoutRequest(
    val refreshToken: String? = null,
)
