package com.kbap.common.application.auth.token

import java.time.Duration

interface RefreshTokenStore {
    fun save(jti: String, memberId: Long, ttl: Duration)

    // 원자적 소비(조회+삭제) — rotation 경합 방어.
    fun consume(jti: String): Long?

    fun delete(jti: String)
}
