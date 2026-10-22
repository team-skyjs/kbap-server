package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.ContentTranslation
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.TextTranslator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private val translatorProvider: ObjectProvider<TextTranslator>,
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
            log.info("가공식품 대표 상품 미리 번역이 이미 도는 중이라 건너뜁니다 languages={}", languages.map { it.code })
            return 0
        }
        try {
            return prewarmFeaturedOnce(languages)
        } finally {
            prewarming.set(false)
        }
    }

    private fun prewarmFeaturedOnce(languages: List<LanguageCode>): Int {
        val rows = processedFoodService.getFeaturedProducts()
        val before = engineCalls.get()
        languages.filter { it != LanguageCode.KO }.forEach { language ->
            rows.forEach { row ->
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
        return brandNames.keys
            .flatMap { brand -> featuredCatalog.makerAliases(brand).map { alias -> brand to alias } }
            .filter { (_, alias) -> alias.isNotEmpty() && alias in key }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.let { (brand, _) -> brandNames[brand] }
    }

    private fun translateAndStore(type: TranslationTargetType, productId: Long, source: String, hash: String, language: LanguageCode): String? {
        val translator = translatorProvider.getIfAvailable() ?: return null
        if (!withinBudget()) {
            log.warn("가공식품 번역 일일 상한(₩{})에 닿아 원문으로 둔다 — type={}, productId={}, language={}", dailyBudgetKrw, type, productId, language.code)
            return null
        }
        engineCalls.incrementAndGet()
        val text = try {
            translator.translate(source, language).text.trim()
        } catch (e: RuntimeException) {
            log.warn("가공식품 번역 실패 — 원문으로 두고 오늘은 다시 부르지 않는다: type={}, productId={}, language={}", type, productId, language.code, e)
            ""
        }
        val usable = text.takeIf { it.isNotEmpty() && it.length <= MAX_TRANSLATED_LENGTH }
        runCatching {
            writeTransaction.executeWithoutResult { translations.upsert(type.name, productId, language.code, hash, usable.orEmpty(), null, LocalDateTime.now()) }
        }.onFailure { log.warn("가공식품 번역 저장 실패 — 번역문은 돌려준다: type={}, productId={}, language={}", type, productId, language.code, it) }
        return usable
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
        private const val MAX_TRANSLATED_LENGTH = 10_000
        private const val BACKGROUND_THREADS = 2
        private const val BACKGROUND_QUEUE = 500
        private val SUMMARY_TYPES = listOf(TranslationTargetType.PF_NAME, TranslationTargetType.PF_MAKER)
        private const val FOREGROUND_THREADS = 4
        private const val FOREGROUND_QUEUE = 50
        private const val BUDGET_KEY_PREFIX = "pf-translate:"
        private val BUDGET_KEY_TTL = Duration.ofDays(2)
        private const val IDLE_WAIT_NANOS = 10_000_000_000L
        private const val IDLE_POLL_MILLIS = 50L
    }
}
