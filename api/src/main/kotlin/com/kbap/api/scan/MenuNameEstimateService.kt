package com.kbap.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.food.AlcoholImage
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.scan.MenuNameEstimateJpaRepository
import com.kbap.common.domain.scan.model.MenuNameEstimate
import com.kbap.common.port.llm.MenuNameEstimation
import com.kbap.common.port.llm.MenuNameEstimator
import com.kbap.common.port.llm.MenuNameKind
import com.kbap.common.util.ImageUrls
import com.kbap.common.util.KoreanMenuNameNormalizer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

enum class EstimatedRisk { CAUTION, UNKNOWN }

object EstimatedRiskJudge {
    fun judge(estimatedCodes: Collection<String>, avoidance: Avoidance): EstimatedRisk =
        if (matchedCodes(estimatedCodes, avoidance).isEmpty()) EstimatedRisk.UNKNOWN else EstimatedRisk.CAUTION

    fun matchedCodes(estimatedCodes: Collection<String>, avoidance: Avoidance): List<String> =
        estimatedCodes.distinct().filter { name ->
            val code = IngredientCode.entries.firstOrNull { it.name == name } ?: return@filter false
            (code.impliedCodes + code).any { it.name in avoidance.codeNames }
        }
}

@Service
class MenuNameEstimateService(
    private val estimates: MenuNameEstimateJpaRepository,
    private val foods: FoodJpaRepository,
    private val images: MenuEstimateImageService,
    private val alcoholImage: AlcoholImage,
    private val redis: StringRedisTemplate,
    private val meters: MeterRegistry,
    estimatorProvider: ObjectProvider<MenuNameEstimator>,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.scan.menu-estimate.daily-name-limit:3000}") private val dailyNameLimit: Long,
    @Value("\${kbap.scan.menu-estimate.member-daily-name-limit:100}") private val memberDailyNameLimit: Long,
    @Value("\${kbap.scan.menu-estimate.wait:15s}") private val wait: Duration,
    @Value("\${kbap.scan.menu-estimate.names-per-call:4}") private val namesPerCall: Int,
    @Value("\${kbap.scan.menu-estimate.concurrency:8}") concurrency: Int,
    @Value("\${kbap.llm.menu-estimate.model:}") private val modelName: String,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val estimator: MenuNameEstimator? = estimatorProvider.getIfAvailable()
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val executor = ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_SIZE), { Thread(it, "menu-estimate").apply { isDaemon = true } })
    private val latency: Timer = Timer.builder("menu_estimate.latency").publishPercentiles(0.5, 0.95).register(meters)

    fun start(member: Member, result: ScanResult): ScanResult {
        val pending = result.items.withIndex().filter { (_, item) -> item.pendingNameKey != null }
        if (pending.isEmpty()) return result
        val scanId = UUID.randomUUID().toString()
        val state = PendingScan(
            memberId = member.id,
            deadlineMillis = System.currentTimeMillis() + wait.toMillis(),
            items = pending.map { (index, item) -> PendingItem("p$index", item.pendingNameKey!!, item.koreanName.orEmpty(), item.foodId, item.price, item.priceOptions) },
        )
        try {
            redis.opsForValue().set(STATE_PREFIX + scanId, mapper.writeValueAsString(state), STATE_TTL)
        } catch (e: RuntimeException) {
            log.warn("메뉴 추정 대기 상태 저장 실패 — scanId 없이 현행 응답", e)
            return result
        }
        if (!member.isBot) {
            try {
                schedule(member.id, state.items.distinctBy { it.nameKey })
            } catch (e: RuntimeException) {
                log.warn("메뉴 추정 예약 실패 — 추정 없이 마감 뒤 UNKNOWN 으로 끝난다 scanId={}", scanId, e)
            }
        }
        val pendingIdByIndex = pending.associate { (index, _) -> index to "p$index" }
        return result.copy(scanId = scanId, items = result.items.mapIndexed { index, item -> item.copy(pendingId = pendingIdByIndex[index]) })
    }

    fun getPendingItems(scanId: String, memberId: Long, avoidance: Avoidance): ScanPendingResponse {
        val state = runCatching { redis.opsForValue().get(STATE_PREFIX + scanId) }.getOrNull()
            ?.let { runCatching { mapper.readValue<PendingScan>(it) }.getOrNull() }
            ?.takeIf { it.memberId == memberId }
            ?: throw BusinessException(ErrorCode.SCAN_PENDING_NOT_FOUND)
        val cached = readTransaction.execute { estimates.findAllByNameKeyIn(state.items.map { it.nameKey }.distinct()) }!!.associateBy { it.nameKey }
        val base = finalOrNull(scanId) ?: run {
            val expired = System.currentTimeMillis() >= state.deadlineMillis
            val items = state.items.mapNotNull { item ->
                cached[item.nameKey]?.let { estimatedItem(item, it, avoidance) } ?: unknownItem(item).takeIf { expired }
            }
            val response = ScanPendingResponse(items, done = expired || items.size == state.items.size)
            if (response.done) finalize(scanId, response) else response
        }
        return withImages(state, fillLateEstimates(state, base, cached, avoidance), cached)
    }

    private fun fillLateEstimates(state: PendingScan, response: ScanPendingResponse, cached: Map<String, MenuNameEstimate>, avoidance: Avoidance): ScanPendingResponse {
        if (response.items.none { it.filled }) return response
        val byPendingId = state.items.associateBy { it.pendingId }
        return response.copy(
            items = response.items.map { item ->
                val pending = byPendingId[item.pendingId]
                val estimate = pending?.let { cached[it.nameKey] }
                if (item.filled && pending != null && estimate != null) estimatedItem(pending, estimate, avoidance) else item
            },
        )
    }

    private fun withImages(state: PendingScan, response: ScanPendingResponse, cached: Map<String, MenuNameEstimate>): ScanPendingResponse {
        val byPendingId = state.items.associateBy { it.pendingId }
        val foodIds = state.items.mapNotNull { it.foodId }.distinct()
        val readyFoods = foodIds.takeIf { it.isNotEmpty() }
            ?.let { ids -> readTransaction.execute { foods.findAllById(ids).filter { it.isReady() && (!it.imageRef.isNullOrBlank() || alcoholImage.urlFor(it) != null) } }!!.associateBy { it.id } }.orEmpty()
        val imageDeadlinePassed = System.currentTimeMillis() >= state.deadlineMillis + IMAGE_EXTRA_WAIT.toMillis()
        val items = response.items.map { item ->
            val pending = byPendingId[item.pendingId]
            val readyFood = item.foodId?.let { readyFoods[it] }
            val estimate = pending?.let { cached[it.nameKey] }
            val withPrice = item.copy(priceKrw = pending?.priceKrw, priceOptions = pending?.priceOptions?.map { ScanV2Response.PriceOptionResponse(it.label, it.priceKrw) })
            val alcoholUrl = estimate?.let { alcoholImage.urlForEstimate(it.kind, it.avoidanceCodes) }
            when {
                readyFood != null -> withPrice.copy(imageStatus = EstimatedImageStatus.READY, imageUrl = alcoholImage.urlFor(readyFood) ?: imageUrlOf(readyFood.imageRef), aiGeneratedImage = false)
                alcoholUrl != null && item.kind != MenuNameKind.NOT_FOOD -> withPrice.copy(imageStatus = EstimatedImageStatus.READY, imageUrl = alcoholUrl, aiGeneratedImage = false)
                item.kind == MenuNameKind.NOT_FOOD || item.filled || estimate == null || (estimate.imageRef == null && !isConfidentEnoughForImage(estimate.confidence)) -> withPrice.copy(imageStatus = EstimatedImageStatus.NONE, imageUrl = null, aiGeneratedImage = false)
                estimate.imageRef != null -> withPrice.copy(imageStatus = EstimatedImageStatus.READY, imageUrl = imageUrlOf(estimate.imageRef), aiGeneratedImage = true)
                imageDeadlinePassed || (!images.isGenerating(estimate.nameKey) && images.isUnavailable(estimate.nameKey)) ->
                    withPrice.copy(imageStatus = EstimatedImageStatus.NONE, imageUrl = null, aiGeneratedImage = false)
                else -> withPrice.copy(imageStatus = EstimatedImageStatus.GENERATING, imageUrl = null, aiGeneratedImage = false)
            }
        }
        return response.copy(items = items, imagesDone = imageDeadlinePassed || (response.done && items.none { it.imageStatus == EstimatedImageStatus.GENERATING }))
    }

    private fun imageUrlOf(ref: String?): String? = ImageUrls.resolve(imagePublicBaseUrl, ref)

    private fun isConfidentEnoughForImage(confidence: BigDecimal?): Boolean =
        confidence != null && confidence.toDouble() >= MenuEstimateImageService.MIN_CONFIDENCE

    private fun finalOrNull(scanId: String): ScanPendingResponse? =
        runCatching { redis.opsForValue().get(FINAL_PREFIX + scanId) }.getOrNull()?.let { runCatching { mapper.readValue<ScanPendingResponse>(it) }.getOrNull() }

    private fun finalize(scanId: String, response: ScanPendingResponse): ScanPendingResponse = try {
        redis.opsForValue().setIfAbsent(FINAL_PREFIX + scanId, mapper.writeValueAsString(response), STATE_TTL)
        finalOrNull(scanId) ?: response
    } catch (e: RuntimeException) {
        log.warn("메뉴 추정 최종 응답 저장 실패 — 이번 응답을 그대로 준다 scanId={}", scanId, e)
        response
    }

    fun deleteEstimate(nameKey: String): Boolean = writeTransaction.execute { estimates.deleteByNameKey(nameKey) }!! > 0

    private fun schedule(memberId: Long, items: List<PendingItem>) {
        val knownRows = readTransaction.execute { estimates.findAllByNameKeyIn(items.map { it.nameKey }) }!!
        val known = knownRows.map { it.nameKey }.toSet()
        images.request(knownRows.filter { it.kind != MenuNameKind.NOT_FOOD.name && it.imageRef == null && isConfidentEnoughForImage(it.confidence) && alcoholImage.urlForEstimate(it.kind, it.avoidanceCodes) == null }.map { it.nameKey })
        meters.counter("menu_estimate.cache", "result", "hit").increment(items.count { it.nameKey in known }.toDouble())
        val missing = items.filter { it.nameKey !in known && it.nameKey.length <= MAX_NAME_KEY_LENGTH && !failedRecently(it.nameKey) }
        meters.counter("menu_estimate.cache", "result", "miss").increment(missing.size.toDouble())
        missing.chunked(namesPerCall.coerceIn(1, MAX_NAMES_PER_CALL)).forEach { chunk ->
            try {
                executor.execute { runChunk(memberId, chunk) }
            } catch (e: RejectedExecutionException) {
                meters.counter("menu_estimate.skipped", "reason", "queue_full").increment(chunk.size.toDouble())
            }
        }
    }

    private fun runChunk(memberId: Long, queued: List<PendingItem>) {
        val estimator = estimator ?: return
        val cachedNow = readTransaction.execute { estimates.findAllByNameKeyIn(queued.map { it.nameKey }) }!!.map { it.nameKey }.toSet()
        val owned = queued.filter { it.nameKey !in cachedNow && !failedRecently(it.nameKey) && acquireInflight(it.nameKey) }
        if (owned.isEmpty()) return
        if (!withinDailyLimits(memberId, owned.size)) {
            meters.counter("menu_estimate.skipped", "reason", "limit").increment(owned.size.toDouble())
            owned.forEach { releaseInflight(it.nameKey) }
            return
        }
        estimateChunk(estimator, owned)
    }

    private fun estimateChunk(estimator: MenuNameEstimator, chunk: List<PendingItem>) {
        val names = chunk.map { it.nameKey }
        meters.counter("menu_estimate.calls").increment()
        meters.summary("menu_estimate.names_per_call").record(names.size.toDouble())
        val sample = Timer.start(meters)
        val results = try {
            estimator.estimate(names)
        } catch (e: RuntimeException) {
            meters.counter("menu_estimate.failures", "reason", e.javaClass.simpleName).increment()
            log.warn("메뉴 이름 추정 호출 실패(일시 장애로 보고 잠시 뒤 다시 시도) names={}", names.size, e)
            chunk.forEach { recordTransientFailure(it.nameKey); releaseInflight(it.nameKey) }
            return
        } finally {
            sample.stop(latency)
        }
        val byKey = chunk.associateBy { it.nameKey }
        val saved = mutableSetOf<String>()
        val imageTargets = mutableListOf<String>()
        results.forEach { estimation ->
            val nameKey = KoreanMenuNameNormalizer.matchKey(estimation.koreanName)
            if (nameKey !in byKey || nameKey in saved) return@forEach meters.counter("menu_estimate.discarded", "reason", "name_mismatch").increment()
            val row = rowOf(nameKey, estimation) ?: return@forEach meters.counter("menu_estimate.discarded", "reason", "invalid").increment()
            writeTransaction.executeWithoutResult {
                estimates.insertIgnore(row.nameKey, row.kind, row.englishName, row.description, mapper.writeValueAsString(row.avoidanceCodes), row.spiciness, row.confidence, row.model, row.promptVersion)
            }
            saved += nameKey
            if (row.kind != MenuNameKind.NOT_FOOD.name && isConfidentEnoughForImage(row.confidence) && alcoholImage.urlForEstimate(row.kind, row.avoidanceCodes) == null) imageTargets += nameKey
            meters.counter("menu_estimate.kind", "kind", row.kind).increment()
            row.confidence?.let { meters.summary("menu_estimate.confidence").record(it.toDouble()) }
        }
        chunk.forEach { if (it.nameKey !in saved) recordFailure(it.nameKey) }
        chunk.forEach { releaseInflight(it.nameKey) }
        images.request(imageTargets)
    }

    private fun rowOf(nameKey: String, estimation: MenuNameEstimation): MenuNameEstimate? {
        if (estimation.spiciness != -1 && estimation.spiciness !in 0..MAX_SPICINESS) return null
        val confidence = estimation.confidence?.takeIf { it in 0.0..1.0 }
        val notFood = estimation.kind == MenuNameKind.NOT_FOOD && (confidence ?: 0.0) >= NOT_FOOD_MIN_CONFIDENCE
        val kind = if (estimation.kind == MenuNameKind.NOT_FOOD && !notFood) MenuNameKind.DISH else estimation.kind
        return MenuNameEstimate(
            nameKey = nameKey,
            kind = kind.name,
            englishName = estimation.englishName?.take(ENGLISH_NAME_LENGTH)?.takeIf { !notFood && it.isNotBlank() },
            description = estimation.description?.take(DESCRIPTION_LENGTH)?.takeIf { !notFood && it.isNotBlank() },
            avoidanceCodes = if (notFood) emptyList() else estimation.avoidanceCodes.map { it.name }.distinct(),
            spiciness = if (notFood) -1 else estimation.spiciness,
            confidence = confidence?.let { BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP) },
            model = modelName.ifBlank { "unknown" }.take(MODEL_LENGTH),
            promptVersion = PROMPT_VERSION,
        )
    }

    private fun estimatedItem(item: PendingItem, estimate: MenuNameEstimate, avoidance: Avoidance): ScanPendingItemResponse {
        val codes = EstimatedRiskJudge.matchedCodes(estimate.avoidanceCodes.orEmpty(), avoidance)
        val risk = if (codes.isEmpty()) EstimatedRisk.UNKNOWN else EstimatedRisk.CAUTION
        meters.counter("menu_estimate.risk", "risk", risk.name).increment()
        return ScanPendingItemResponse(
            pendingId = item.pendingId,
            kind = MenuNameKind.entries.firstOrNull { it.name == estimate.kind } ?: MenuNameKind.DISH,
            name = estimate.englishName ?: item.koreanName,
            koreanName = item.koreanName,
            description = estimate.description,
            riskLevel = risk,
            avoidances = codes,
            estimatedCodes = estimate.avoidanceCodes.orEmpty(),
            spiciness = estimate.spiciness,
            confidence = estimate.confidence?.toDouble(),
            existsNotReady = item.foodId != null,
            foodId = item.foodId,
        )
    }

    private fun unknownItem(item: PendingItem) = ScanPendingItemResponse(
        pendingId = item.pendingId,
        kind = MenuNameKind.DISH,
        name = item.koreanName,
        koreanName = item.koreanName,
        description = null,
        riskLevel = EstimatedRisk.UNKNOWN,
        avoidances = emptyList(),
        spiciness = -1,
        confidence = null,
        existsNotReady = item.foodId != null,
        foodId = item.foodId,
        filled = true,
    )

    private fun failedRecently(nameKey: String): Boolean =
        runCatching { redis.hasKey(FAIL_PREFIX + nameKey) }.getOrDefault(false)

    private fun recordTransientFailure(nameKey: String) {
        runCatching { redis.opsForValue().set(FAIL_PREFIX + nameKey, "transient", TRANSIENT_FAIL_TTL) }
            .onFailure { log.warn("메뉴 이름 추정 일시 실패 표시 저장 실패 nameKey={}", nameKey, it) }
    }

    private fun recordFailure(nameKey: String) {
        runCatching {
            val count = redis.opsForValue().increment(FAIL_COUNT_PREFIX + nameKey) ?: 1
            redis.expire(FAIL_COUNT_PREFIX + nameKey, LONG_FAIL_TTL)
            redis.opsForValue().set(FAIL_PREFIX + nameKey, count.toString(), if (count >= FAILS_FOR_LONG_BACKOFF) LONG_FAIL_TTL else FAIL_TTL)
        }.onFailure { log.warn("메뉴 이름 추정 실패 표시 저장 실패 nameKey={}", nameKey, it) }
    }

    private fun acquireInflight(nameKey: String): Boolean =
        runCatching { redis.opsForValue().setIfAbsent(INFLIGHT_PREFIX + nameKey, "1", INFLIGHT_TTL) == true }.getOrDefault(false)

    private fun releaseInflight(nameKey: String) {
        runCatching { redis.delete(INFLIGHT_PREFIX + nameKey) }
    }

    private fun withinDailyLimits(memberId: Long, names: Int): Boolean = try {
        val today = LocalDate.now(SEOUL).format(DateTimeFormatter.BASIC_ISO_DATE)
        val memberKey = DAILY_PREFIX + today + ":member:" + memberId
        when {
            !reserve(memberKey, names, memberDailyNameLimit) -> false
            !reserve(DAILY_PREFIX + today, names, dailyNameLimit) -> { redis.opsForValue().decrement(memberKey, names.toLong()); false }
            else -> true
        }
    } catch (e: RuntimeException) {
        log.warn("메뉴 이름 추정 일일 상한 계수 실패 — 추정하지 않는다", e)
        false
    }

    private fun reserve(key: String, names: Int, limit: Long): Boolean {
        val used = redis.opsForValue().increment(key, names.toLong()) ?: return false
        if (used == names.toLong()) redis.expire(key, DAILY_TTL)
        if (used <= limit) return true
        redis.opsForValue().decrement(key, names.toLong())
        return false
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    data class PendingScan(val memberId: Long = 0, val deadlineMillis: Long = 0, val items: List<PendingItem> = emptyList())

    data class PendingItem(
        val pendingId: String = "",
        val nameKey: String = "",
        val koreanName: String = "",
        val foodId: Long? = null,
        val priceKrw: Int? = null,
        val priceOptions: List<ScanResult.PriceOption>? = null,
    )

    companion object {
        const val PROMPT_VERSION = "v1"
        const val STATE_PREFIX = "scan:"
        const val DAILY_PREFIX = "menu-estimate:"
        const val FAIL_PREFIX = "menu-estimate:fail:"
        const val FINAL_PREFIX = "scan-final:"
        private const val FAIL_COUNT_PREFIX = "menu-estimate:fail-count:"
        private const val INFLIGHT_PREFIX = "menu-estimate:inflight:"
        private val mapper = jacksonObjectMapper()
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val STATE_TTL = Duration.ofMinutes(10)
        private val IMAGE_EXTRA_WAIT = Duration.ofSeconds(45)
        private val DAILY_TTL = Duration.ofDays(2)
        private val FAIL_TTL = Duration.ofDays(1)
        private val TRANSIENT_FAIL_TTL = Duration.ofMinutes(10)
        private val LONG_FAIL_TTL = Duration.ofDays(7)
        private const val FAILS_FOR_LONG_BACKOFF = 3L
        private val INFLIGHT_TTL = Duration.ofSeconds(30)
        private const val MAX_NAMES_PER_CALL = 30
        private const val MAX_NAME_KEY_LENGTH = 40
        private const val QUEUE_SIZE = 100
        private const val NOT_FOOD_MIN_CONFIDENCE = 0.8
        private const val MAX_SPICINESS = 10
        private const val ENGLISH_NAME_LENGTH = 200
        private const val DESCRIPTION_LENGTH = 200
        private const val MODEL_LENGTH = 64
    }
}
