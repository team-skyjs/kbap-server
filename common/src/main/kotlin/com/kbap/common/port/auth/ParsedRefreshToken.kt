package com.kbap.common.port.auth

data class ParsedRefreshToken(
    val memberId: Long,
    val jti: String,
)
