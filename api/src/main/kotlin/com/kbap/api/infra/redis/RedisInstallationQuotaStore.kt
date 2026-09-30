package com.kbap.api.infra.redis

import com.kbap.common.port.quota.InstallationQuotaStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisInstallationQuotaStore(
    private val redisTemplate: StringRedisTemplate,
) : InstallationQuotaStore {
    override fun tryAcquire(scope: String, installationId: String, requestId: String, limit: Int, window: Duration): Boolean {
        val now = System.currentTimeMillis()
        val result = redisTemplate.execute(
            ACQUIRE_SCRIPT,
            listOf(key(scope, installationId)),
            (now - window.toMillis()).toString(),
            now.toString(),
            limit.toString(),
            requestId,
            window.toMillis().toString(),
        )
        return result == ACQUIRED
    }

    override fun release(scope: String, installationId: String, requestId: String) {
        redisTemplate.opsForZSet().remove(key(scope, installationId), requestId)
    }

    private fun key(scope: String, installationId: String): String = "$scope:quota:$installationId"

    companion object {
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
