package com.meogo.app.api.auth

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank(message = "idToken 은 필수입니다")
    val idToken: String,
)
