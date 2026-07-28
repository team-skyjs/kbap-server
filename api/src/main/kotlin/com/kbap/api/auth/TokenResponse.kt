package com.kbap.api.auth

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
) {
    companion object {
        fun from(result: RefreshResult): TokenResponse =
            TokenResponse(accessToken = result.accessToken, refreshToken = result.refreshToken)
    }
}
