package com.kbap.api.infra.redis

import com.kbap.common.port.auth.IssuedRefreshToken
import com.kbap.common.port.auth.RefreshRotation
import com.kbap.common.port.auth.RefreshTokenStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisRefreshTokenStore(
    private val redisTemplate: StringRedisTemplate,
) : RefreshTokenStore {
    override fun save(jti: String, memberId: Long, ttl: Duration) {
        redisTemplate.opsForValue().set(key(jti), memberId.toString(), ttl)
    }

    override fun rotate(jti: String, successor: IssuedRefreshToken, ttl: Duration, grace: Duration): RefreshRotation? {
        val result = redisTemplate.execute(
            ROTATE,
            listOf(key(jti), key(successor.jti)),
            successor.jti,
            successor.token,
            ttl.toMillis().toString(),
            grace.toMillis().toString(),
            KEY_PREFIX,
        ) ?: return null
        val memberId = result.getOrNull(0)?.toString()?.toLongOrNull() ?: return null
        val refreshToken = result.getOrNull(1)?.toString() ?: return null
        return RefreshRotation(memberId, refreshToken)
    }

    override fun delete(jti: String) {
        redisTemplate.delete(key(jti))
    }

    private fun key(jti: String): String = "$KEY_PREFIX$jti"

    companion object {
        private const val KEY_PREFIX = "auth:refresh:"
        private const val ROTATED = "r|"

        private val ROTATE: RedisScript<List<*>> = RedisScript.of(
            """
            local current = redis.call('GET', KEYS[1])
            if not current then return nil end
            if string.sub(current, 1, 2) == '$ROTATED' then
              local rest = string.sub(current, 3)
              local a = string.find(rest, '|', 1, true)
              local b = string.find(rest, '|', a + 1, true)
              local member = string.sub(rest, 1, a - 1)
              local successorJti = string.sub(rest, a + 1, b - 1)
              local successorToken = string.sub(rest, b + 1)
              if redis.call('EXISTS', ARGV[5] .. successorJti) == 0 then return nil end
              return { member, successorToken }
            end
            redis.call('SET', KEYS[2], current, 'PX', ARGV[3])
            redis.call('SET', KEYS[1], '$ROTATED' .. current .. '|' .. ARGV[1] .. '|' .. ARGV[2], 'PX', ARGV[4])
            return { current, ARGV[2] }
            """.trimIndent(),
            List::class.java,
        )
    }
}
