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
    override fun tryAcquire(
        scope: String,
        installationId: String,
        requestId: String,
        limit: Int,
        window: Duration,
        recordedAtMillis: List<Long>,
    ): Boolean {
        val now = System.currentTimeMillis()
        val result = redisTemplate.execute(
            ACQUIRE_SCRIPT,
            listOf(key(scope, installationId)),
            (now - window.toMillis()).toString(),
            now.toString(),
            limit.toString(),
            requestId,
            window.toMillis().toString(),
            *recordedAtMillis.map { it.toString() }.toTypedArray(),
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
            if redis.call('EXISTS', KEYS[1]) == 0 then
                for i = 6, #ARGV do
                    redis.call('ZADD', KEYS[1], ARGV[i], 'recorded:' .. i)
                end
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local acquired = 0
            if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[3]) then
                redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4])
                acquired = 1
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return acquired
            """.trimIndent(),
            Long::class.java,
        )
    }
}
