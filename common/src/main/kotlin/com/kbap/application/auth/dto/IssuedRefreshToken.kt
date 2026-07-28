package com.kbap.application.auth.dto

data class IssuedRefreshToken(
    val token: String,
    val jti: String,
)
