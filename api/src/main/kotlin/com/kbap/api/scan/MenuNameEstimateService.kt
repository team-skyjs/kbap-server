package com.kbap.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.food.AlcoholImage
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
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

    internal fun savePendingScan(scanId: String, state: PendingScan) {
        redis.opsForValue().set(STATE_PREFIX + scanId, mapper.writeValueAsString(state), STATE_TTL)
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

    private fun withImages(
        state: PendingScan,
        response: ScanPendingResponse,
        cached: Map<String, MenuNameEstimate>,
    ): ScanPendingResponse {
        val pendingById = state.items.associateBy { it.pendingId }
        val foodIds = response.items.mapNotNull { it.foodId }.toSet()
        val foodImageUrls = loadReadyFoodImageUrls(foodIds)
        val deadlinePassed = System.currentTimeMillis() >= state.imageDeadlineMillis

        val items = mutableListOf<ScanPendingItemResponse>()
        for (item in response.items) {
            val pending = pendingById[item.pendingId]
            val pricedItem = item.copy(
                priceKrw = pending?.priceKrw,
                priceOptions = pending?.priceOptions?.map { ScanV2Response.PriceOptionResponse(it.label, it.priceKrw) },
            )
            items.add(selectItemImage(pricedItem, foodImageUrls[item.foodId], cached[pending?.nameKey], deadlinePassed))
        }
        val anyImageGenerating = items.any { it.imageStatus == EstimatedImageStatus.GENERATING }
        return response.copy(items = items, imagesDone = deadlinePassed || (response.done && !anyImageGenerating))
    }

    private fun loadReadyFoodImageUrls(foodIds: Set<Long>): Map<Long, String> {
        if (foodIds.isEmpty()) return emptyMap()

        return readTransaction.execute {
            val urls = mutableMapOf<Long, String>()
            for (food in foods.findAllById(foodIds)) {
                if (!food.isReady()) continue
                val url = alcoholImage.urlFor(food) ?: imageUrlOf(food.imageRef?.takeUnless { it.isBlank() })
                if (url != null) urls[food.id] = url
            }
            urls
        }
    }

    private fun selectItemImage(
        item: ScanPendingItemResponse,
        foodImageUrl: String?,
        estimate: MenuNameEstimate?,
        deadlinePassed: Boolean,
    ): ScanPendingItemResponse {
        // 1. 등록 음식 사진 또는 공용 주류 이미지
        if (foodImageUrl != null) {
            return item.copy(imageStatus = EstimatedImageStatus.READY, imageUrl = foodImageUrl, aiGeneratedImage = false)
        }
        val noImage = item.copy(imageStatus = EstimatedImageStatus.NONE, imageUrl = null, aiGeneratedImage = false)
        if (item.kind == MenuNameKind.NOT_FOOD || estimate == null) return noImage

        val alcoholUrl = alcoholImage.urlForEstimate(estimate.kind, estimate.effectiveAvoidanceCodes())
        if (alcoholUrl != null) {
            return item.copy(imageStatus = EstimatedImageStatus.READY, imageUrl = alcoholUrl, aiGeneratedImage = false)
        }

        // 2. 저장된 AI 이미지
        if (item.filled) return noImage
        if (estimate.imageRef != null) {
            return item.copy(imageStatus = EstimatedImageStatus.READY, imageUrl = imageUrlOf(estimate.imageRef), aiGeneratedImage = true)
        }

        // 3. 이미지 생성 대기 여부
        if (deadlinePassed || !isConfidentEnoughForImage(estimate.confidence)) return noImage
        if (!images.isGenerating(estimate.nameKey) && images.isUnavailable(estimate.nameKey)) return noImage
        return noImage.copy(imageStatus = EstimatedImageStatus.GENERATING)
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

    fun schedule(memberId: Long, items: List<PendingItem>) {
        // 1. 기존 추정 조회 및 누락된 이미지 요청
        val nameKeys = items.map { it.nameKey }.distinct()
        val cachedEstimates = readTransaction.execute { estimates.findAllByNameKeyIn(nameKeys) }!!
        val cachedNames = mutableSetOf<String>()
        val namesNeedingImages = mutableListOf<String>()
        for (estimate in cachedEstimates) {
            cachedNames.add(estimate.nameKey)
            if (needsGeneratedImage(estimate)) namesNeedingImages.add(estimate.nameKey)
        }
        images.request(namesNeedingImages)
        meters.counter("menu_estimate.cache", "result", "hit").increment(nameKeys.count { it in cachedNames }.toDouble())

        // 2. 새로 추정할 메뉴 선별
        val namesToEstimate = mutableListOf<String>()
        for (name in nameKeys) {
            if (name in cachedNames) continue
            if (name.length > MAX_NAME_KEY_LENGTH) continue
            if (failedRecently(name)) continue
            namesToEstimate.add(name)
        }
        meters.counter("menu_estimate.cache", "result", "miss").increment(namesToEstimate.size.toDouble())

        // 3. 스캔당 작업 하나 등록, 작업 안에서 묶음별 순차 호출
        if (namesToEstimate.isEmpty()) return
        val chunkSize = namesPerCall.coerceIn(1, MAX_NAMES_PER_CALL)
        try {
            executor.execute {
                for (chunk in namesToEstimate.chunked(chunkSize)) {
                    try {
                        estimateMenuNames(memberId, chunk)
                    } catch (e: RuntimeException) {
                        log.warn("메뉴 추정 묶음 처리 실패 memberId={} names={}", memberId, chunk.size, e)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            meters.counter("menu_estimate.skipped", "reason", "queue_full").increment(namesToEstimate.size.toDouble())
        }
    }

    private fun needsGeneratedImage(estimate: MenuNameEstimate): Boolean {
        if (estimate.imageRef != null || estimate.kind == MenuNameKind.NOT_FOOD.name) return false
        if (!isConfidentEnoughForImage(estimate.confidence)) return false
        return alcoholImage.urlForEstimate(estimate.kind, estimate.effectiveAvoidanceCodes()) == null
    }

    private fun estimateMenuNames(memberId: Long, queuedNames: List<String>) {
        val estimator = estimator ?: return

        val names = claimNamesForEstimation(queuedNames)
        if (names.isEmpty()) return

        val imageTargets = try {
            if (!withinDailyLimits(memberId, names.size)) {
                meters.counter("menu_estimate.skipped", "reason", "limit").increment(names.size.toDouble())
                return
            }
            val results = requestEstimates(estimator, names) ?: return
            saveEstimates(names, results)
        } finally {
            for (name in names) releaseInflight(name)
        }
        images.request(imageTargets)
    }

    private fun claimNamesForEstimation(queuedNames: List<String>): List<String> {
        // 큐 대기 중 다른 요청이 완료했거나 처리 중인 이름 제외
        val cached = readTransaction.execute { estimates.findAllByNameKeyIn(queuedNames) }
        val cachedNames = cached.map { it.nameKey }.toSet()
        val names = mutableListOf<String>()
        for (name in queuedNames) {
            if (name in cachedNames || failedRecently(name)) continue
            if (acquireInflight(name)) names.add(name)
        }
        return names
    }

    private fun requestEstimates(estimator: MenuNameEstimator, names: List<String>): List<MenuNameEstimation>? {
        meters.counter("menu_estimate.calls").increment()
        meters.summary("menu_estimate.names_per_call").record(names.size.toDouble())
        val sample = Timer.start(meters)
        return try {
            estimator.estimate(names)
        } catch (e: RuntimeException) {
            meters.counter("menu_estimate.failures", "reason", e.javaClass.simpleName).increment()
            log.warn("메뉴 이름 추정 호출 실패(일시 장애로 보고 잠시 뒤 다시 시도) names={}", names.size, e)
            for (name in names) recordTransientFailure(name)
            null
        } finally {
            sample.stop(latency)
        }
    }

    private fun saveEstimates(names: List<String>, results: List<MenuNameEstimation>): List<String> {
        val saved = mutableSetOf<String>()
        val imageTargets = mutableListOf<String>()
        for (estimation in results) {
            val nameKey = KoreanMenuNameNormalizer.matchKey(estimation.koreanName)
            if (nameKey !in names || nameKey in saved) {
                meters.counter("menu_estimate.discarded", "reason", "name_mismatch").increment()
                continue
            }
            val row = rowOf(nameKey, estimation)
            if (row == null) {
                meters.counter("menu_estimate.discarded", "reason", "invalid").increment()
                continue
            }
            writeTransaction.executeWithoutResult {
                estimates.insertIgnore(row.nameKey, row.kind, row.englishName, row.description, mapper.writeValueAsString(row.avoidanceCodes), row.spiciness, row.confidence, row.model, row.promptVersion)
            }
            saved += nameKey
            if (needsGeneratedImage(row)) imageTargets += nameKey
            meters.counter("menu_estimate.kind", "kind", row.kind).increment()
            row.confidence?.let { meters.summary("menu_estimate.confidence").record(it.toDouble()) }
        }
        for (name in names) {
            if (name !in saved) recordFailure(name)
        }
        return imageTargets
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
        val codes = EstimatedRiskJudge.matchedCodes(estimate.effectiveAvoidanceCodes(), avoidance)
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
            estimatedCodes = estimate.effectiveAvoidanceCodes(),
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

    data class PendingScan(
        val memberId: Long = 0,
        val deadlineMillis: Long = 0,
        val items: List<PendingItem> = emptyList(),
        // 기존 Redis 기록에는 이미지 마감이 없어 당시의 추가 대기시간 적용
        val imageDeadlineMillis: Long = deadlineMillis + Duration.ofSeconds(45).toMillis(),
    )

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
