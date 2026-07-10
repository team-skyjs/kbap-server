package com.meogo.core.member

import java.time.Duration

interface RefreshTokenStore {
    fun save(jti: String, memberId: Long, ttl: Duration)

    fun consume(jti: String): Long?

    fun delete(jti: String)
}
