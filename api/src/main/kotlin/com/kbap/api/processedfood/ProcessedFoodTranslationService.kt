package com.kbap.api.processedfood

import io.swagger.v3.oas.annotations.media.Schema
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.llm.TranslationRejectedException
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.ContentTranslation
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.ProcessedFoodTextTranslator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service
class ProcessedFoodTranslationService(
    private val translations: ContentTranslationJpaRepository,
    private val translatorProvider: ObjectProvider<ProcessedFoodTextTranslator>,
    private val featuredCatalog: ProcessedFoodFeaturedCatalog,
    private val processedFoodService: ProcessedFoodService,
    private val redis: StringRedisTemplate,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.processed-food.translation.daily-budget-krw:5000}") var dailyBudgetKrw: Double,
    @Value("\${kbap.processed-food.translation.estimated-krw-per-call:0.4}") private val estimatedKrwPerCall: Double,
    @Value("\${kbap.processed-food.translation.sync-deadline-ms:3000}") var syncDeadlineMillis: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val background = executor("pf-translation", BACKGROUND_THREADS, BACKGROUND_QUEUE)
    private val foreground = executor("pf-translation-sync", FOREGROUND_THREADS, FOREGROUND_QUEUE)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<String?>>()
    private val engineCalls = AtomicInteger()
    private val prewarming = AtomicBoolean(false)
    private val brandNames: Map<String, String> =
        requireNotNull(javaClass.getResourceAsStream("/processed-food/maker-names.tsv")) { "maker-names.tsv" }
            .bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .associate { it[0].trim() to it[1].trim() }

    fun localizeDetail(detail: ProcessedFoodDetailResponse, language: LanguageCode, member: Boolean): ProcessedFoodDetailResponse {
        if (language == LanguageCode.KO) return detail
        val unclearLabels = UnclearIngredientLabels.labelsOf(detail.unclearIngredientLabels.map { it.term }, language)
        val deadline = if (member) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(syncDeadlineMillis) else null
        val name = request(TranslationTargetType.PF_NAME, detail.id, detail.productName, language, deadline != null)
        val maker = detail.maker?.let { if (brandNameOf(it) != null) null else request(TranslationTargetType.PF_MAKER, detail.id, it, language, deadline != null) }
        val ingredients = request(TranslationTargetType.PF_INGREDIENTS, detail.id, detail.ingredients?.original, language, sync = false)
        val allergens = request(TranslationTargetType.PF_ALLERGENS, detail.id, detail.allergenStatement?.original, language, sync = false)
        return detail.copy(
            name = name.await(deadline) ?: detail.productName,
            maker = detail.maker?.let { brandNameOf(it) ?: maker.await(deadline) ?: it },
            ingredients = detail.ingredients?.let { it.copy(translated = ingredients.await(null) ?: it.original) },
            allergenStatement = detail.allergenStatement?.let { it.copy(translated = allergens.await(null) ?: it.original) },
            unclearIngredientLabels = unclearLabels,
        )
    }

    fun localizeSummaries(items: List<ProcessedFoodSummaryResponse>, language: LanguageCode): List<ProcessedFoodSummaryResponse> {
        if (language == LanguageCode.KO || items.isEmpty()) return items
        val cache = readTransaction.execute {
            translations.findAllByTargetTypeInAndTargetIdInAndLanguage(SUMMARY_TYPES, items.map { it.id }, language.code)
        }!!.associateBy { it.targetType to it.targetId }
        return items.map { item ->
            item.copy(
                name = request(TranslationTargetType.PF_NAME, item.id, item.productName, language, sync = false, cache[TranslationTargetType.PF_NAME to item.id]).await(null) ?: item.productName,
                maker = item.maker?.let {
                    brandNameOf(it) ?: request(TranslationTargetType.PF_MAKER, item.id, it, language, sync = false, cache[TranslationTargetType.PF_MAKER to item.id]).await(null) ?: it
                },
            )
        }
    }

    fun prewarmFeatured(languages: List<LanguageCode>): Int {
        if (!prewarming.compareAndSet(false, true)) {
            log.info("가공식품 대표 상품 미리 번역이 이 태스크에서 이미 도는 중이라 건너뜁니다 languages={}", languages.map { it.code })
            return 0
        }
        try {
            val owner = UUID.randomUUID().toString()
            val locked = runCatching { redis.opsForValue().setIfAbsent(PREWARM_LOCK_KEY, owner, PREWARM_LOCK_TTL) == true }
                .onFailure { log.warn("가공식품 미리 번역 공유 락을 잡지 못해 건너뜁니다", it) }
                .getOrDefault(false)
            if (!locked) {
                log.info("가공식품 대표 상품 미리 번역이 다른 태스크에서 도는 중이라 건너뜁니다 languages={}", languages.map { it.code })
                return 0
            }
            try {
                return prewarmFeaturedOnce(languages) {
                    runCatching { redis.execute(RENEW_IF_OWNER, listOf(PREWARM_LOCK_KEY), owner, PREWARM_LOCK_TTL.toMillis().toString()) == 1L }.getOrDefault(false)
                }
            } finally {
                runCatching { redis.execute(DELETE_IF_OWNER, listOf(PREWARM_LOCK_KEY), owner) }
            }
        } finally {
            prewarming.set(false)
        }
    }

    private fun prewarmFeaturedOnce(languages: List<LanguageCode>, stillOwned: () -> Boolean): Int {
        val rows = processedFoodService.getFeaturedProducts()
        val before = engineCalls.get()
        languages.filter { it != LanguageCode.KO }.forEach { language ->
            rows.forEach { row ->
                if (!stillOwned()) {
                    log.warn("가공식품 미리 번역 공유 락을 잃어 멈춥니다 languages={}", languages.map { it.code })
                    return engineCalls.get() - before
                }
                listOf(
                    TranslationTargetType.PF_NAME to row.productName,
                    TranslationTargetType.PF_MAKER to row.displayMaker()?.takeIf { brandNameOf(it) == null },
                    TranslationTargetType.PF_INGREDIENTS to row.rawMaterials?.takeUnless(IngredientLabelReader::isRawNotProvided),
                    TranslationTargetType.PF_ALLERGENS to row.allergens?.takeUnless(IngredientLabelReader::isNotProvided),
                ).forEach { (type, source) -> source?.takeIf { it.isNotBlank() }?.let { cachedOrNull(type, row.id, it, language) ?: translateAndStore(type, row.id, it, hashOf(it), language) } }
            }
        }
        val translated = engineCalls.get() - before
        log.info("가공식품 대표 상품 미리 번역 완료 languages={} products={} translated={}", languages.map { it.code }, rows.size, translated)
        return translated
    }

    fun prewarmFeaturedInBackground(languages: List<LanguageCode>) {
        background.execute { runCatching { prewarmFeatured(languages) }.onFailure { log.warn("가공식품 대표 상품 미리 번역 실패", it) } }
    }

    fun awaitIdle() {
        val deadline = System.nanoTime() + IDLE_WAIT_NANOS
        while ((inFlight.isNotEmpty() || prewarming.get()) && System.nanoTime() < deadline) Thread.sleep(IDLE_POLL_MILLIS)
    }

    private sealed interface Pending
    private data class Ready(val text: String?) : Pending
    private data class Running(val future: CompletableFuture<String?>?) : Pending

    private fun Pending?.await(deadline: Long?): String? = when (this) {
        null -> null
        is Ready -> text
        is Running -> if (future == null || deadline == null) null else try {
            future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun request(
        type: TranslationTargetType,
        productId: Long,
        source: String?,
        language: LanguageCode,
        sync: Boolean,
        cached: ContentTranslation? = readTransaction.execute { translations.findByTargetTypeAndTargetIdAndLanguage(type, productId, language.code) },
    ): Pending? {
        if (source.isNullOrBlank()) return null
        val hash = hashOf(source)
        if (cached != null && cached.sourceHash == hash) {
            if (cached.translatedText.isNotBlank()) return Ready(cached.translatedText)
            if (!cached.updatedAt.isBefore(LocalDate.now().atStartOfDay())) return Ready(null)
        }
        val key = "${type.name}:$productId:${language.code}:$hash"
        inFlight[key]?.let { return Running(it) }
        val future = CompletableFuture<String?>()
        if (inFlight.putIfAbsent(key, future) != null) return Running(inFlight[key])
        try {
            (if (sync) foreground else background).execute {
                try {
                    future.complete(translateAndStore(type, productId, source, hash, language))
                } catch (e: RuntimeException) {
                    future.complete(null)
                } finally {
                    inFlight.remove(key, future)
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight.remove(key, future)
            return Running(null)
        }
        return Running(future)
    }

    fun regenerateNames(dryRun: Boolean, limit: Int, afterId: Long?): ProcessedFoodNameRegeneration {
        val total = readTransaction.execute { translations.countByTargetType(TranslationTargetType.PF_NAME) }!!
        val rows = readTransaction.execute { translations.findPageByTargetType(TranslationTargetType.PF_NAME.name, afterId ?: 0, limit + 1) }!!
        val page = rows.take(limit)
        val next = page.lastOrNull()?.id?.takeIf { rows.size > limit }
        val estimate = total * estimatedKrwPerCall
        if (dryRun) return ProcessedFoodNameRegeneration(true, total, page.size, 0, emptyList(), next, estimate, emptyList(), budgetExhausted = false)
        val failed = mutableListOf<Long>()
        val samples = mutableListOf<ProcessedFoodNameRegenerationSample>()
        var regenerated = 0
        var lastDone = afterId
        var budgetExhausted = false
        for (row in page) {
            val name = runCatching { processedFoodService.getAvailableProduct(row.targetId).productName }.getOrNull()
            val attempt = name?.let { attemptTranslation(TranslationTargetType.PF_NAME, row.targetId, it, hashOf(it), LanguageCode.from(row.language), keepExistingOnReject = true) }
            if (attempt?.budgetExhausted == true) {
                budgetExhausted = true
                break
            }
            lastDone = row.id
            val after = attempt?.text
            if (after == null || name == null) {
                failed += row.targetId
                continue
            }
            regenerated++
            if (samples.size < SAMPLE_SIZE) samples += ProcessedFoodNameRegenerationSample(row.targetId, row.language, name, row.translatedText, after)
        }
        return ProcessedFoodNameRegeneration(false, total, page.size, regenerated, failed, if (budgetExhausted) lastDone else next, estimate, samples, budgetExhausted)
    }

    private fun cachedOrNull(type: TranslationTargetType, productId: Long, source: String, language: LanguageCode): String? {
        val cached = readTransaction.execute { translations.findByTargetTypeAndTargetIdAndLanguage(type, productId, language.code) }
        return cached?.takeIf { it.sourceHash == hashOf(source) && (it.translatedText.isNotBlank() || !it.updatedAt.isBefore(LocalDate.now().atStartOfDay())) }?.translatedText
    }

    private fun withinBudget(): Boolean = try {
        val key = BUDGET_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val attempts = redis.opsForValue().increment(key) ?: return false
        if (attempts == 1L) redis.expire(key, BUDGET_KEY_TTL)
        attempts * estimatedKrwPerCall <= dailyBudgetKrw
    } catch (e: RuntimeException) {
        log.warn("가공식품 번역 일일 상한 계수 실패 — 번역하지 않고 원문으로 둔다", e)
        false
    }

    private fun brandNameOf(maker: String): String? {
        val key = ProcessedFoodSnapshot.normalizeName(maker).lowercase()
        brandNames.keys.firstOrNull { ProcessedFoodSnapshot.normalizeName(it).lowercase() == key }?.let { return brandNames[it] }
        return brandNames.keys
            .flatMap { brand -> featuredCatalog.makerAliases(brand).map { alias -> brand to alias } }
            .filter { (_, alias) -> alias.isNotEmpty() && alias in key }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.let { (brand, _) -> brandNames[brand] }
    }

    private fun translateAndStore(type: TranslationTargetType, productId: Long, source: String, hash: String, language: LanguageCode): String? =
        attemptTranslation(type, productId, source, hash, language).text

    private class Attempt(val text: String?, val budgetExhausted: Boolean)

    private fun attemptTranslation(
        type: TranslationTargetType,
        productId: Long,
        source: String,
        hash: String,
        language: LanguageCode,
        keepExistingOnReject: Boolean = false,
    ): Attempt {
        val translator = translatorProvider.getIfAvailable() ?: return Attempt(null, budgetExhausted = false)
        if (!withinBudget()) {
            log.warn("가공식품 번역 일일 상한(₩{})에 닿아 원문으로 둔다 — type={}, productId={}, language={}", dailyBudgetKrw, type, productId, language.code)
            return Attempt(null, budgetExhausted = true)
        }
        engineCalls.incrementAndGet()
        val text = try {
            (if (type == TranslationTargetType.PF_NAME) translator.translateName(source, language) else translator.translate(source, language)).text.trim()
        } catch (e: TranslationRejectedException) {
            log.warn("가공식품 번역이 가드에 거절됐다 — 원문을 이 원문 해시의 번역으로 남겨 다시 부르지 않는다: type={}, productId={}, language={}, reason={}", type, productId, language.code, e.message)
            if (keepExistingOnReject) return Attempt(null, budgetExhausted = false)
            source
        } catch (e: RuntimeException) {
            log.warn("가공식품 번역 실패 — 원문으로 두고 오늘은 다시 부르지 않는다: type={}, productId={}, language={}", type, productId, language.code, e)
            ""
        }
        val usable = text.takeIf { it.isNotEmpty() && it.length <= MAX_TRANSLATED_LENGTH }
        runCatching {
            writeTransaction.executeWithoutResult { translations.upsert(type.name, productId, language.code, hash, usable.orEmpty(), null, LocalDateTime.now()) }
        }.onFailure { log.warn("가공식품 번역 저장 실패 — 번역문은 돌려준다: type={}, productId={}, language={}", type, productId, language.code, it) }
        return Attempt(usable, budgetExhausted = false)
    }

    private fun executor(name: String, threads: Int, queue: Int): ExecutorService = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(queue),
        { Thread(it, name).apply { isDaemon = true } },
    )

    private fun hashOf(source: String): String =
        MessageDigest.getInstance("SHA-256").digest("$CACHE_VERSION\n$source".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        fun languageOf(lang: String?): LanguageCode = lang?.takeIf { it.isNotBlank() }?.let(LanguageCode::from) ?: LanguageCode.KO

        const val CACHE_VERSION = "pf1"
        private const val SAMPLE_SIZE = 10
        private const val MAX_TRANSLATED_LENGTH = 10_000
        private const val BACKGROUND_THREADS = 2
        private const val BACKGROUND_QUEUE = 500
        private val SUMMARY_TYPES = listOf(TranslationTargetType.PF_NAME, TranslationTargetType.PF_MAKER)
        private const val FOREGROUND_THREADS = 4
        private const val FOREGROUND_QUEUE = 50
        private const val BUDGET_KEY_PREFIX = "pf-translate:"
        private const val PREWARM_LOCK_KEY = "pf-translate:prewarm"
        private val PREWARM_LOCK_TTL = Duration.ofHours(2)
        private val RENEW_IF_OWNER = DefaultRedisScript(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            Long::class.java,
        )
        private val DELETE_IF_OWNER = DefaultRedisScript(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long::class.java,
        )
        private val BUDGET_KEY_TTL = Duration.ofDays(2)
        private const val IDLE_WAIT_NANOS = 10_000_000_000L
        private const val IDLE_POLL_MILLIS = 50L
    }
}

@Schema(description = "가공식품 이름 번역 재생성 결과")
data class ProcessedFoodNameRegeneration(
    @field:Schema(description = "true 면 건수·비용 추정만 하고 번역을 부르지 않았다")
    val dryRun: Boolean,
    @field:Schema(description = "저장된 PF_NAME 번역 전체 행 수")
    val total: Long,
    @field:Schema(description = "이번 호출에서 본 행 수")
    val processed: Int,
    @field:Schema(description = "다시 번역해 덮어쓴 행 수")
    val regenerated: Int,
    @field:Schema(description = "상품을 못 찾거나(단종·삭제) 번역 실패·가드 거절·일일 상한으로 못 바꾼 상품 id")
    val failedProductIds: List<Long>,
    @field:Schema(description = "다음 호출에 afterId 로 넘길 커서 — 끝이면 null. budgetExhausted 면 막힌 행 바로 앞(그 행부터 다시)", nullable = true)
    val nextAfterId: Long?,
    @field:Schema(description = "전체 재생성 비용 추정(원) = total × 호출당 추정 단가")
    val estimatedTotalKrw: Double,
    @field:Schema(description = "앞 10건의 전·후")
    val samples: List<ProcessedFoodNameRegenerationSample>,
    @field:Schema(description = "true 면 일일 번역 상한에 닿아 그 행에서 멈췄다 — 다음 날 nextAfterId 로 이어 부른다(null 이면 처음부터)")
    val budgetExhausted: Boolean,
)

@Schema(description = "이름 번역 재생성 전·후")
data class ProcessedFoodNameRegenerationSample(val productId: Long, val language: String, val name: String, val before: String, val after: String)

