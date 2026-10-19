package com.kbap.common.port.auth

data class RefreshRotation(
    val memberId: Long,
    val refreshToken: String,
)
