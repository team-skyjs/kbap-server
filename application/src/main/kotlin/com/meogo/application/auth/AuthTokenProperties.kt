package com.meogo.application.auth

import java.time.Duration

data class AuthTokenProperties(
    val secret: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) {
            "meogo.auth.jwt.secret 는 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다"
        }
    }

    companion object {
        private const val MIN_SECRET_BYTES = 32
    }
}
