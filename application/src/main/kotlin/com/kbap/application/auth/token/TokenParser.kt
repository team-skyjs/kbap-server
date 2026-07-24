package com.kbap.application.auth.token

import com.kbap.application.auth.dto.ParsedAccessToken
import com.kbap.application.auth.dto.ParsedRefreshToken

interface TokenParser {
    fun parseAccessToken(token: String): ParsedAccessToken

    fun parseRefreshToken(token: String): ParsedRefreshToken

    fun refreshTokenJtiOrNull(token: String): String?
}
