package com.kbap.api.auth

data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
)
