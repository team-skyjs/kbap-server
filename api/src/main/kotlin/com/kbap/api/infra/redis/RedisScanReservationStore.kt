package com.kbap.api.infra.redis

import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class RedisScanReservationStore(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${kbap.scan.reservation-ttl-seconds:300}") private val reservationTtlSeconds: Long,
) : ScanReservationStore {
    override fun reserve(memberId: Long, requestId: String, confirmedCount: Int, limit: Int): ScanReservationResult {
        val now = System.currentTimeMillis()
        val ttlMillis = reservationTtlSeconds * 1000
        val result = redisTemplate.execute(
            RESERVE_SCRIPT,
            listOf(key(memberId)),
            now.toString(),
            (now + ttlMillis).toString(),
            confirmedCount.toString(),
            limit.toString(),
            requestId,
            ttlMillis.toString(),
        )
        return when (result) {
            RESULT_RESERVED -> ScanReservationResult.RESERVED
            RESULT_DUPLICATE -> ScanReservationResult.DUPLICATE_REQUEST
            else -> ScanReservationResult.LIMIT_EXCEEDED
        }
    }

    override fun release(memberId: Long, requestId: String) {
        redisTemplate.opsForZSet().remove(key(memberId), requestId)
    }

    private fun key(memberId: Long): String = "$KEY_PREFIX$memberId"

    companion object {
        private const val KEY_PREFIX = "scan:reservations:"
        private const val RESULT_RESERVED = 1L
        private const val RESULT_DUPLICATE = 2L

        private val RESERVE_SCRIPT = DefaultRedisScript(
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZSCORE', KEYS[1], ARGV[5]) then
                return 2
            end
            local reserved = redis.call('ZCARD', KEYS[1])
            if tonumber(ARGV[3]) + reserved >= tonumber(ARGV[4]) then
                return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
