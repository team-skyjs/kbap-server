package com.kbap.common.port.auth

data class IssuedRefreshToken(
    val token: String,
    val jti: String,
)
