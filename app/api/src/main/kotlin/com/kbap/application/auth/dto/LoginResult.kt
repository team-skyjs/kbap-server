package com.kbap.application.auth.dto

data class LoginResult(
    val memberId: Long,
    val newMember: Boolean,
    val accessToken: String,
    val refreshToken: String,
)
