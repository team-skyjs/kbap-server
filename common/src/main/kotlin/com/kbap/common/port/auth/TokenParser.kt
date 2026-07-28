package com.kbap.common.port.auth

import com.kbap.common.port.auth.ParsedAccessToken
import com.kbap.common.port.auth.ParsedRefreshToken

interface TokenParser {
    fun parseAccessToken(token: String): ParsedAccessToken

    fun parseRefreshToken(token: String): ParsedRefreshToken

    fun refreshTokenJtiOrNull(token: String): String?
}
