package com.meogo.infra.persistence.auth

import com.meogo.core.member.RefreshTokenStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class RefreshTokenRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
) : RefreshTokenStore {
    override fun save(jti: String, memberId: Long, ttl: Duration) {
        redisTemplate.opsForValue().set(key(jti), memberId.toString(), ttl)
    }

    override fun consume(jti: String): Long? =
        redisTemplate.opsForValue().getAndDelete(key(jti))?.toLongOrNull()

    override fun delete(jti: String) {
        redisTemplate.delete(key(jti))
    }

    private fun key(jti: String): String = "$KEY_PREFIX$jti"

    companion object {
        private const val KEY_PREFIX = "auth:refresh:"
    }
}
