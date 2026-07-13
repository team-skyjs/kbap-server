package com.kbap.application.auth.dto

data class ParsedRefreshToken(
    val memberId: Long,
    val jti: String,
)
