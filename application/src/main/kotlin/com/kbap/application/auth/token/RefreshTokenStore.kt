package com.kbap.application.auth.token

import java.time.Duration

// refresh token 세션 저장소 seam — 구현은 :infra:redis.
interface RefreshTokenStore {
    fun save(jti: String, memberId: Long, ttl: Duration)

    // 원자적 소비(조회+삭제) — rotation 경합 방어.
    fun consume(jti: String): Long?

    fun delete(jti: String)
}
