package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.TextTranslator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class ProcessedFoodTexts(val name: String, val maker: String?, val ingredients: String?, val allergens: String?)

@Service
class ProcessedFoodTranslationService(
    private val translations: ContentTranslationJpaRepository,
    private val translatorProvider: ObjectProvider<TextTranslator>,
    private val featuredCatalog: ProcessedFoodFeaturedCatalog,
    private val products: ProcessedFoodProductJpaRepository,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.processed-food.translation.daily-budget-krw:5000}") var dailyBudgetKrw: Double,
    @Value("\${kbap.processed-food.translation.estimated-krw-per-call:0.4}") private val estimatedKrwPerCall: Double,
    @Value("\${kbap.processed-food.translation.max-concurrent-engine-calls:2}") maxConcurrentEngineCalls: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val enginePermits = Semaphore(maxConcurrentEngineCalls)
    private val background = Executors.newFixedThreadPool(BACKGROUND_THREADS) { Thread(it, "pf-translation").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val brandNames: Map<String, String> =
        requireNotNull(javaClass.getResourceAsStream("/processed-food/maker-names.tsv")) { "maker-names.tsv" }
            .bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .associate { it[0].trim() to it[1].trim() }

    fun localizeDetail(detail: ProcessedFoodDetailResponse, language: LanguageCode): ProcessedFoodDetailResponse {
        if (language == LanguageCode.KO) return detail
        val texts = textsOf(detail.id, detail.productName, detail.maker, detail.ingredients?.original, detail.allergenStatement?.original, language, waitForEngine = true)
        return detail.copy(
            name = texts.name,
            maker = texts.maker,
            ingredients = detail.ingredients?.let { it.copy(translated = texts.ingredients ?: it.original) },
            allergenStatement = detail.allergenStatement?.let { it.copy(translated = texts.allergens ?: it.original) },
        )
    }

    fun localizeSummaries(items: List<ProcessedFoodSummaryResponse>, language: LanguageCode): List<ProcessedFoodSummaryResponse> {
        if (language == LanguageCode.KO) return items
        return items.map { item ->
            val texts = textsOf(item.id, item.productName, item.maker, null, null, language, waitForEngine = false)
            item.copy(name = texts.name, maker = texts.maker)
        }
    }

    private fun textsOf(productId: Long, name: String, maker: String?, ingredients: String?, allergens: String?, language: LanguageCode, waitForEngine: Boolean): ProcessedFoodTexts {
        fun field(type: TranslationTargetType, source: String?): String? =
            source?.takeIf { it.isNotBlank() }?.let { translated(type, productId, it, language, waitForEngine) ?: it }
        return ProcessedFoodTexts(
            name = field(TranslationTargetType.PF_NAME, name) ?: name,
            maker = maker?.let { brandNameOf(it) ?: field(TranslationTargetType.PF_MAKER, it) },
            ingredients = field(TranslationTargetType.PF_INGREDIENTS, ingredients),
            allergens = field(TranslationTargetType.PF_ALLERGENS, allergens),
        )
    }

    fun prewarmFeatured(languages: List<LanguageCode>): Int {
        val names = featuredCatalog.productsByPriority().map { ProcessedFoodSnapshot.normalizeName(it.name) }
        val rows = readTransaction.execute { products.findAllByNormalizedNameIn(names) }!!
            .groupBy { it.normalizedName }.values
            .map { group -> group.maxWith(compareBy<ProcessedFoodProduct>({ it.lastSeenAt }, { it.id })) }
        val before = countEngineCallsToday()
        languages.filter { it != LanguageCode.KO }.forEach { language ->
            rows.forEach { row ->
                textsOf(
                    row.id, row.productName, row.displayMaker(),
                    row.rawMaterials?.takeUnless(IngredientLabelReader::isRawNotProvided),
                    row.allergens?.takeUnless(IngredientLabelReader::isNotProvided),
                    language, waitForEngine = true,
                )
            }
        }
        val translated = (countEngineCallsToday() - before).toInt()
        log.info("가공식품 대표 상품 미리 번역 완료 languages={} products={} translated={}", languages.map { it.code }, rows.size, translated)
        return translated
    }

    fun prewarmFeaturedInBackground(languages: List<LanguageCode>) {
        background.execute { runCatching { prewarmFeatured(languages) }.onFailure { log.warn("가공식품 대표 상품 미리 번역 실패", it) } }
    }

    fun awaitIdle() {
        val deadline = System.nanoTime() + IDLE_WAIT_NANOS
        while (inFlight.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(IDLE_POLL_MILLIS)
    }

    private fun brandNameOf(maker: String): String? {
        val key = ProcessedFoodSnapshot.normalizeName(maker).lowercase()
        return brandNames.keys
            .flatMap { brand -> featuredCatalog.makerAliases(brand).map { alias -> brand to alias } }
            .filter { (_, alias) -> alias.isNotEmpty() && alias in key }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.let { (brand, _) -> brandNames[brand] }
    }

    private fun translated(type: TranslationTargetType, productId: Long, source: String, language: LanguageCode, waitForEngine: Boolean): String? {
        val hash = hashOf(source)
        val cached = readTransaction.execute { translations.findByTargetTypeAndTargetIdAndLanguage(type, productId, language.code) }
        if (cached != null && cached.sourceHash == hash) return cached.translatedText
        if (!waitForEngine) {
            val key = "${type.name}:$productId:${language.code}"
            if (inFlight.add(key)) {
                background.execute {
                    try {
                        translateAndStore(type, productId, source, hash, language)
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }
            return null
        }
        return translateAndStore(type, productId, source, hash, language)
    }

    private fun translateAndStore(type: TranslationTargetType, productId: Long, source: String, hash: String, language: LanguageCode): String? {
        if (countEngineCallsToday() * estimatedKrwPerCall + estimatedKrwPerCall > dailyBudgetKrw) {
            log.warn("가공식품 번역 일일 상한(₩{})에 닿아 원문으로 둔다 — type={}, productId={}, language={}", dailyBudgetKrw, type, productId, language.code)
            return null
        }
        val translator = translatorProvider.getIfAvailable() ?: return null
        if (!enginePermits.tryAcquire()) return null
        val text = try {
            translator.translate(source, language).text.trim()
        } catch (e: RuntimeException) {
            log.warn("가공식품 번역 실패 — 원문으로 둔다: type={}, productId={}, language={}", type, productId, language.code, e)
            return null
        } finally {
            enginePermits.release()
        }
        if (text.isEmpty() || text.length > MAX_TRANSLATED_LENGTH) return null
        runCatching {
            writeTransaction.executeWithoutResult { translations.upsert(type.name, productId, language.code, hash, text, null, LocalDateTime.now()) }
        }.onFailure { log.warn("가공식품 번역 저장 실패 — 번역문은 돌려준다: type={}, productId={}, language={}", type, productId, language.code, it) }
        return text
    }

    private fun countEngineCallsToday(): Long =
        readTransaction.execute { translations.countByTargetTypeInAndUpdatedAtGreaterThanEqual(PRODUCT_TYPES, LocalDate.now().atStartOfDay()) }!!

    private fun hashOf(source: String): String =
        MessageDigest.getInstance("SHA-256").digest("$CACHE_VERSION\n$source".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        fun languageOf(lang: String?): LanguageCode = lang?.takeIf { it.isNotBlank() }?.let(LanguageCode::from) ?: LanguageCode.KO

        const val CACHE_VERSION = "pf1"
        private const val MAX_TRANSLATED_LENGTH = 10_000
        private const val BACKGROUND_THREADS = 2
        private const val IDLE_WAIT_NANOS = 10_000_000_000L
        private const val IDLE_POLL_MILLIS = 50L
        private val PRODUCT_TYPES = listOf(TranslationTargetType.PF_NAME, TranslationTargetType.PF_MAKER, TranslationTargetType.PF_INGREDIENTS, TranslationTargetType.PF_ALLERGENS)
    }
}
