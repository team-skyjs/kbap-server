package com.kbap.infra.auth.token

import java.time.Duration

data class JwtTokenProperties(
    val secret: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) {
            "kbap.auth.jwt.secret 는 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다"
        }
    }

    companion object {
        private const val MIN_SECRET_BYTES = 32
    }
}
