package com.kbap.infra.redis

import com.kbap.common.port.auth.RefreshTokenStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisRefreshTokenStore(
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
