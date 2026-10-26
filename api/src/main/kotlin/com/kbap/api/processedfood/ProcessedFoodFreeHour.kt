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

    fun open(memberId: Long): Instant {
        val until = clock.millis() + WINDOW.toMillis()
        val write = {
            runCatching { redis.opsForValue().set(keyOf(memberId), until.toString(), WINDOW + CLEANUP_SLACK) }
                .onFailure { log.warn("가공식품 무료 창을 열지 못했습니다(차감은 됨) memberId={}", memberId, it) }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    private var written = false

                    override fun beforeCommit(readOnly: Boolean) {
                        written = write().isSuccess
                    }

                    override fun afterCompletion(status: Int) {
                        if (written && status != TransactionSynchronization.STATUS_COMMITTED) {
                            runCatching { redis.execute(DELETE_IF_SAME, listOf(keyOf(memberId)), until.toString()) }
                                .onFailure { log.warn("커밋되지 않은 차감의 가공식품 무료 창을 지우지 못했습니다 memberId={}", memberId, it) }
                        }
                    }
                },
            )
        } else {
            write()
        }
        return Instant.ofEpochMilli(until)
    }

    fun freeUntil(memberId: Long): Instant? = runCatching {
        redis.opsForValue().get(keyOf(memberId))?.toLongOrNull()?.takeIf { it > clock.millis() }?.let(Instant::ofEpochMilli)
    }.getOrNull()

    private fun keyOf(memberId: Long) = "pf-free:$memberId"

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(60)
        private val CLEANUP_SLACK: Duration = Duration.ofMinutes(5)
        private val DELETE_IF_SAME = DefaultRedisScript(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }
}
