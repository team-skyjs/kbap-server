package com.kbap.application.auth.dto

data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
)
