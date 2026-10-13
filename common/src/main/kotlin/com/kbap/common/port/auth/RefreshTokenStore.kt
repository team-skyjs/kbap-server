package com.kbap.common.port.auth

import java.time.Duration

interface RefreshTokenStore {
    fun save(jti: String, memberId: Long, ttl: Duration)

    fun rotate(jti: String, memberId: Long, successor: IssuedRefreshToken, ttl: Duration, grace: Duration): RefreshRotation?

    fun delete(jti: String)
}
