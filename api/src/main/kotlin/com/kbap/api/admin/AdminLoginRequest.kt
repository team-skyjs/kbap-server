package com.kbap.api.admin

import jakarta.validation.constraints.NotBlank

data class AdminLoginRequest(
    @field:NotBlank
    val id: String? = null,
    @field:NotBlank
    val password: String? = null,
)

data class AdminLoginResponse(
    val accessToken: String,
)
