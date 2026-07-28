package com.kbap.common.application.auth.dto

data class ParsedRefreshToken(
    val memberId: Long,
    val jti: String,
)
