package com.kbap.application.auth

data class IssuedRefreshToken(
    val token: String,
    val jti: String,
)
