package com.kbap.api.infra.redis

import com.kbap.common.port.auth.LoginAttemptStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisLoginAttemptStore(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${kbap.auth.admin.login-lock-max-attempts:5}") private val maxAttempts: Int,
    @Value("\${kbap.auth.admin.login-lock-duration:15m}") private val lockDuration: Duration,
) : LoginAttemptStore {
    override fun isLocked(key: String): Boolean =
        (redisTemplate.opsForValue().get(redisKey(key))?.toIntOrNull() ?: 0) >= maxAttempts

    override fun recordFailure(key: String): Int {
        val count = redisTemplate.opsForValue().increment(redisKey(key)) ?: 1L
        redisTemplate.expire(redisKey(key), lockDuration)
        return count.toInt()
    }

    override fun reset(key: String) {
        redisTemplate.delete(redisKey(key))
    }

    private fun redisKey(key: String): String = "$KEY_PREFIX$key"

    companion object {
        private const val KEY_PREFIX = "admin:login-fail:"
    }
}
