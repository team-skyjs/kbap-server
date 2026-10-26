package com.kbap.api.processedfood

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class ProcessedFoodFreeHour(private val redis: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    var clock: Clock = Clock.systemDefaultZone()

    data class Window(val freeUntil: Instant, val opened: Boolean)

    fun claim(memberId: Long): Window? {
        val now = clock.millis()
        val until = now + WINDOW.toMillis()
        val result = runCatching {
            redis.execute(CLAIM, listOf(keyOf(memberId)), now.toString(), until.toString(), (WINDOW + CLEANUP_SLACK).toMillis().toString())
        }.onFailure { log.warn("가공식품 무료 창을 확인하지 못해 평소대로 차감합니다 memberId={}", memberId, it) }.getOrNull() ?: return null
        val window = if (result > 0) Window(Instant.ofEpochMilli(result), opened = true) else Window(Instant.ofEpochMilli(-result), opened = false)
        if (window.opened && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            runCatching { redis.execute(DELETE_IF_SAME, listOf(keyOf(memberId)), result.toString()) }
                                .onFailure { log.warn("차감이 롤백된 가공식품 무료 창을 지우지 못했습니다 memberId={}", memberId, it) }
                        }
                    }
                },
            )
        }
        return window
    }

    fun freeUntil(memberId: Long): Instant? = runCatching {
        redis.opsForValue().get(keyOf(memberId))?.toLongOrNull()?.takeIf { it > clock.millis() }?.let(Instant::ofEpochMilli)
    }.getOrNull()

    private fun keyOf(memberId: Long) = "pf-free:$memberId"

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(60)
        private val CLEANUP_SLACK: Duration = Duration.ofMinutes(5)
        private val CLAIM = DefaultRedisScript(
            """
            local current = tonumber(redis.call('GET', KEYS[1]))
            if current and current > tonumber(ARGV[1]) then return -current end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return tonumber(ARGV[2])
            """.trimIndent(),
            Long::class.java,
        )
        private val DELETE_IF_SAME = DefaultRedisScript(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }
}
