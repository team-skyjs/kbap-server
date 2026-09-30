package com.kbap.api.infra.redis

import com.kbap.common.port.feedback.FeedbackQuotaStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisFeedbackQuotaStore(
    private val redisTemplate: StringRedisTemplate,
) : FeedbackQuotaStore {
    override fun tryAcquire(installationId: String, requestId: String, limit: Int, window: Duration): Boolean {
        val now = System.currentTimeMillis()
        val result = redisTemplate.execute(
            ACQUIRE_SCRIPT,
            listOf(key(installationId)),
            (now - window.toMillis()).toString(),
            now.toString(),
            limit.toString(),
            requestId,
            window.toMillis().toString(),
        )
        return result == ACQUIRED
    }

    override fun release(installationId: String, requestId: String) {
        redisTemplate.opsForZSet().remove(key(installationId), requestId)
    }

    private fun key(installationId: String): String = "$KEY_PREFIX$installationId"

    companion object {
        private const val KEY_PREFIX = "feedback:quota:"
        private const val ACQUIRED = 1L

        private val ACQUIRE_SCRIPT = DefaultRedisScript(
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
                return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
