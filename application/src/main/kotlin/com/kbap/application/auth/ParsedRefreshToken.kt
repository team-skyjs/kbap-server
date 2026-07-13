package com.kbap.application.auth

data class ParsedRefreshToken(
    val memberId: Long,
    val jti: String,
)
