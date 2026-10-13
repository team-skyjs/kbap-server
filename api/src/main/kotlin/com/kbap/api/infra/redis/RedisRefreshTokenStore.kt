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

    override fun rotate(jti: String, memberId: Long, successor: IssuedRefreshToken, ttl: Duration, grace: Duration): RefreshRotation? {
        redisTemplate.opsForValue().set(key(successor.jti), memberId.toString(), ttl)
        val result = redisTemplate.execute(
            MARK_ROTATED,
            listOf(key(jti)),
            memberId.toString(),
            successor.jti,
            successor.token,
            grace.toMillis().toString(),
        )
        if (result?.firstOrNull() == JUST_ROTATED) return RefreshRotation(memberId, successor.token)
        redisTemplate.delete(key(successor.jti))
        val earlier = (result?.getOrNull(1) as? String)?.let(::parseRotated) ?: return null
        if (redisTemplate.hasKey(key(earlier.successorJti)) != true) return null
        return RefreshRotation(earlier.memberId, earlier.successorToken)
    }

    override fun delete(jti: String) {
        val previous = redisTemplate.opsForValue().getAndDelete(key(jti)) ?: return
        parseRotated(previous)?.let { redisTemplate.delete(key(it.successorJti)) }
    }

    private fun key(jti: String): String = "$KEY_PREFIX$jti"

    private fun parseRotated(value: String): Rotated? {
        if (!value.startsWith(ROTATED_PREFIX)) return null
        val parts = value.removePrefix(ROTATED_PREFIX).split(SEPARATOR, limit = 3)
        if (parts.size != 3) return null
        val memberId = parts[0].toLongOrNull() ?: return null
        return Rotated(memberId, parts[1], parts[2])
    }

    private data class Rotated(val memberId: Long, val successorJti: String, val successorToken: String)

    companion object {
        private const val KEY_PREFIX = "auth:refresh:"
        private const val ROTATED_PREFIX = "r|"
        private const val SEPARATOR = "|"
        private const val JUST_ROTATED = "rotated"

        private val MARK_ROTATED: RedisScript<List<*>> = RedisScript.of(
            """
            local current = redis.call('GET', KEYS[1])
            if not current then return { 'missing' } end
            if string.sub(current, 1, 2) == '$ROTATED_PREFIX' then return { 'already', current } end
            if current ~= ARGV[1] then return { 'mismatch' } end
            redis.call('SET', KEYS[1], '$ROTATED_PREFIX' .. current .. '$SEPARATOR' .. ARGV[2] .. '$SEPARATOR' .. ARGV[3], 'PX', ARGV[4])
            return { '$JUST_ROTATED' }
            """.trimIndent(),
            List::class.java,
        )
    }
}
