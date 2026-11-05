package com.kbap.api.scan

import com.kbap.api.food.FoodImageProperties
import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.domain.scan.MenuNameEstimateJpaRepository
import com.kbap.common.port.llm.FoodImageGenerator
import com.kbap.common.port.llm.GeneratedFoodImage
import com.kbap.common.port.storage.StorageObjectStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class MenuEstimateImageService(
    private val estimates: MenuNameEstimateJpaRepository,
    private val generator: FoodImageGenerator,
    private val storage: StorageObjectStore,
    private val redis: StringRedisTemplate,
    private val meters: MeterRegistry,
    private val eventPublisher: ApplicationEventPublisher,
    private val imageProperties: FoodImageProperties,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.scan.menu-estimate.image.enabled:true}") private val enabled: Boolean,
    @Value("\${kbap.scan.menu-estimate.image.quality:medium}") private val quality: String,
    @Value("\${kbap.scan.menu-estimate.image.daily-limit:300}") private val dailyLimit: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val executor = ThreadPoolExecutor(CONCURRENT_CALLS, CONCURRENT_CALLS, 0, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_SIZE), { Thread(it, "menu-estimate-image").apply { isDaemon = true } })
    private val latency: Timer = Timer.builder("menu_estimate.image.latency").publishPercentiles(0.5, 0.95).register(meters)

    fun request(nameKeys: Collection<String>) {
        if (!enabled) return
        nameKeys.distinct().filter { !isUnavailable(it) && acquire(it) }.forEach { nameKey ->
            try {
                executor.execute { generate(nameKey) }
            } catch (e: RejectedExecutionException) {
                meters.counter("menu_estimate.image.skipped", "reason", "queue_full").increment()
                release(nameKey)
            }
        }
    }

    fun isGenerating(nameKey: String): Boolean = runCatching { redis.hasKey(INFLIGHT_PREFIX + nameKey) }.getOrDefault(false)

    fun isUnavailable(nameKey: String): Boolean = !enabled || runCatching { redis.hasKey(NONE_PREFIX + nameKey) }.getOrDefault(false)

    private fun generate(nameKey: String) {
        try {
            if (!withinDailyLimit()) {
                meters.counter("menu_estimate.image.skipped", "reason", "limit").increment()
                markUnavailable(nameKey, LIMIT_TTL)
                return
            }
            val sample = Timer.start(meters)
            val image = try {
                generator.generate(imageProperties.promptFor(nameKey), quality)
            } finally {
                sample.stop(latency)
            }
            val key = storageKeyOf(nameKey)
            storage.put(key, image.bytes, "image/webp")
            writeTransaction.executeWithoutResult { estimates.attachImage(nameKey, key) }
            meters.counter("menu_estimate.image.generated").increment()
            publishCost(image)
        } catch (e: RuntimeException) {
            meters.counter("menu_estimate.image.failures", "reason", e.javaClass.simpleName).increment()
            log.warn("메뉴 추정 이미지 생성 실패 — 한동안 다시 시도하지 않는다 nameKey={}", nameKey, e)
            markUnavailable(nameKey, FAIL_TTL)
        } finally {
            release(nameKey)
        }
    }

    private fun withinDailyLimit(): Boolean {
        val key = DAILY_PREFIX + LocalDate.now(SEOUL).format(DateTimeFormatter.BASIC_ISO_DATE)
        val count = redis.opsForValue().increment(key) ?: return false
        if (count == 1L) redis.expire(key, DAILY_TTL)
        if (count <= dailyLimit) return true
        redis.opsForValue().decrement(key)
        return false
    }

    private fun publishCost(image: GeneratedFoodImage) {
        val costUsd = BigDecimal.valueOf(image.inputTokens).multiply(BigDecimal.valueOf(imageProperties.inputUsdPerMillionTokens * SYNC_PRICE_FACTOR))
            .add(BigDecimal.valueOf(image.outputTokens).multiply(BigDecimal.valueOf(imageProperties.outputUsdPerMillionTokens * SYNC_PRICE_FACTOR)))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
        eventPublisher.publishEvent(
            LlmCallCostIncurred(
                modelName = imageProperties.model,
                inputTokens = image.inputTokens,
                outputTokens = image.outputTokens,
                costUsd = costUsd,
                costKrw = costUsd.multiply(BigDecimal.valueOf(imageProperties.usdToKrw)).setScale(2, RoundingMode.HALF_UP),
            ),
        )
    }

    private fun acquire(nameKey: String): Boolean =
        runCatching { redis.opsForValue().setIfAbsent(INFLIGHT_PREFIX + nameKey, "1", INFLIGHT_TTL) == true }.getOrDefault(false)

    private fun release(nameKey: String) {
        runCatching { redis.delete(INFLIGHT_PREFIX + nameKey) }
    }

    private fun markUnavailable(nameKey: String, ttl: Duration) {
        runCatching { redis.opsForValue().set(NONE_PREFIX + nameKey, "1", ttl) }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        const val INFLIGHT_PREFIX = "menu-estimate:image-inflight:"
        const val NONE_PREFIX = "menu-estimate:image-none:"
        const val DAILY_PREFIX = "menu-estimate:image-daily:"
        private const val SYNC_PRICE_FACTOR = 2.0
        private const val CONCURRENT_CALLS = 2
        private const val QUEUE_SIZE = 50
        private val INFLIGHT_TTL = Duration.ofMinutes(3)
        private val FAIL_TTL = Duration.ofHours(1)
        private val LIMIT_TTL = Duration.ofMinutes(30)
        private val DAILY_TTL = Duration.ofDays(2)
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun storageKeyOf(nameKey: String): String {
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(nameKey.toByteArray())).take(12)
            return "images/webp/menu-estimate/${hash}_${UUID.randomUUID().toString().replace("-", "").take(16)}.webp"
        }
    }
}
