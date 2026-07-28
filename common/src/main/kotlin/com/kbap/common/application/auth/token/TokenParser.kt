package com.kbap.common.application.auth.token

import com.kbap.common.application.auth.dto.ParsedAccessToken
import com.kbap.common.application.auth.dto.ParsedRefreshToken

interface TokenParser {
    fun parseAccessToken(token: String): ParsedAccessToken

    fun parseRefreshToken(token: String): ParsedRefreshToken

    fun refreshTokenJtiOrNull(token: String): String?
}
