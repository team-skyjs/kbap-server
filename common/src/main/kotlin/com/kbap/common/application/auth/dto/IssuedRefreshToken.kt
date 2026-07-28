package com.kbap.common.application.auth.dto

data class IssuedRefreshToken(
    val token: String,
    val jti: String,
)
