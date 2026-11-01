package com.kbap.api.reviewbot

import com.kbap.api.processedfood.ProcessedFoodCandidateIndex
import com.kbap.api.processedfood.ProcessedFoodCvsCatalog
import com.kbap.api.processedfood.ProcessedFoodService
import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.port.llm.ProcessedFoodReviewDraft
import com.kbap.common.port.llm.ProcessedFoodReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator
import io.swagger.v3.oas.annotations.media.Schema
import org.slf4j.LoggerFactory
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

@Service
class ReviewBotProcessedFoodService(
    private val jdbc: JdbcTemplate,
    private val processedFoods: ProcessedFoodService,
    private val candidateIndex: ProcessedFoodCandidateIndex,
    private val cvsCatalog: ProcessedFoodCvsCatalog,
    private val accountService: ReviewBotAccountService,
    private val reviewService: ReviewService,
    private val photoPool: ReviewBotPhotoPool,
    private val photoService: ReviewBotPhotoService,
    private val lockProvider: LockProvider,
    private val generator: ReviewTextGenerator?,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(target: String, dryRun: Boolean, limit: Int, ids: List<Long>?, topLines: Int?, seed: Long?): ReviewBotProcessedFoodRunResult {
        if (target != environment) throw BusinessException(ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH)
        if (limit !in 1..MAX_LIMIT || (!dryRun && seed == null) || (topLines != null && topLines < 1)) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val generator = generator ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val bots = accountService.getBots().ifEmpty { throw BusinessException(ErrorCode.INVALID_REQUEST) }
        if (dryRun) return plan(dryRun, limit, ids, topLines, seed, generator, bots)
        val lock = lockProvider.lock(LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO)).orElse(null)
            ?: throw BusinessException(ErrorCode.REVIEW_BOT_BUSY)
        try {
            return plan(dryRun, limit, ids, topLines, seed, generator, bots)
        } finally {
            lock.unlock()
        }
    }

    private fun plan(
        dryRun: Boolean,
        limit: Int,
        ids: List<Long>?,
        topLines: Int?,
        seed: Long?,
        generator: ReviewTextGenerator,
        bots: List<Member>,
    ): ReviewBotProcessedFoodRunResult {
        val runSeed = seed ?: Random.Default.nextLong()
        val targets = targetsOf(ids, topLines)
        val ownerIds = photoPool.ownerIds()
        val availableByKey = runCatching { photoService.getInventory().available.groupBy { ReviewBotPhotoPool.keyOfPath(it) } }
            .onFailure { log.warn("가공식품 리뷰 봇 — 사진 재고를 못 읽어 이번 실행은 텍스트만 쓴다", it) }
            .getOrDefault(emptyMap())

        val items = mutableListOf<ReviewBotProcessedFoodItem>()
        val failed = linkedSetOf<Long>()
        var planned = 0
        var written = 0
        for ((rank, product) in targets) {
            if (planned >= limit) break
            val members = candidateIndex.groupMemberIds(product.id)
            val reviewed = botReviewsOf(members)
            val random = Random(runSeed * SEED_SPREAD + product.id)
            val need = targetCountOf(rank, random) - reviewed.size
            val eligible = bots.filterNot { it.id in reviewed.keys }.sortedBy { it.id }.shuffled(random)
            var earlier = reviewed.values.lastOrNull()
            val writers = eligible.take(maxOf(0, minOf(need, limit - planned)))
            val dates = createdAtsOf(writers.size, latestReviewAtOf(members), random)
            for ((index, bot) in writers.withIndex()) {
                planned++
                val draft = draftFor(product, bot, earlier, random, generator)
                if (draft == null) {
                    failed += product.id
                    continue
                }
                val createdAt = dates[index]
                val photos = availableByKey[ReviewBotPhotoPool.keyOf(PHOTO_KEY_PREFIX + product.reportNo)].orEmpty()
                    .let { if (it.isEmpty()) emptyList() else ReviewBotPhotoPool.pickCombination(it, emptySet(), random) }
                    .takeIf { it.isNotEmpty() && photoService.isStillUsable(it) }.orEmpty()
                if (!dryRun && !save(product, bot, draft.second, draft.first, photos, ownerIds, createdAt)) {
                    failed += product.id
                    continue
                }
                if (!dryRun) written++
                earlier = draft.first.text
                items += ReviewBotProcessedFoodItem(
                    product.id, product.reportNo, product.productName, bot.id, draft.second.language, draft.second.rating,
                    draft.first.spiciness, draft.first.sweetness, draft.first.text, createdAt,
                )
            }
        }
        return ReviewBotProcessedFoodRunResult(dryRun, runSeed, targets.size, planned, written, failed.toList(), items)
    }

    private fun targetsOf(ids: List<Long>?, topLines: Int?): List<Pair<Int, ProcessedFoodProduct>> {
        if (ids != null) {
            val rankByReportNo = cvsCatalog.seedLines.flatMap { line -> line.reportNos.map { it to line.rank } }.groupBy({ it.first }, { it.second }).mapValues { (_, ranks) -> ranks.min() }
            return ids.distinct().map { id -> processedFoods.getAvailableProduct(id).let { (rankByReportNo[it.reportNo] ?: Int.MAX_VALUE) to it } }
        }
        return processedFoods.getSeedLineRepresentatives().sortedBy { it.first }.take(topLines ?: Int.MAX_VALUE)
    }

    private fun botReviewsOf(productIds: List<Long>): Map<Long, String?> {
        if (productIds.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT r.member_id, r.content FROM food_review r JOIN member m ON m.id = r.member_id " +
                "WHERE r.status = 'ACTIVE' AND m.is_bot = 1 AND r.processed_food_id IN (${productIds.joinToString(",")}) ORDER BY r.id",
        ) { rs, _ -> rs.getLong(1) to rs.getString(2) }.toMap()
    }

    private fun draftFor(
        product: ProcessedFoodProduct,
        bot: Member,
        earlier: String?,
        random: Random,
        generator: ReviewTextGenerator,
    ): Pair<ProcessedFoodReviewDraft, ProcessedFoodReviewDraftRequest>? {
        val language = ReviewBotCountries.languageFor(bot.countryCode, random)
        val base = ProcessedFoodReviewDraftRequest(
            productName = product.productName,
            foodType = product.foodType,
            ingredients = product.rawMaterials.orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_INGREDIENTS),
            language = language,
            rating = ReviewBotCountries.rating(random),
            maxCharacters = ReviewBotContentGuard.maxLengthFor(language),
            style = ReviewBotStyles.pick(random),
            sentences = ReviewBotStyles.sentences(random),
            avoidSimilarTo = earlier,
        )
        repeat(MAX_ATTEMPTS) { attempt ->
            val request = if (attempt == 0) base else base.copy(style = ReviewBotStyles.pick(random), sentences = ReviewBotStyles.sentences(random))
            val draft = runCatching { generator.generateProcessedFood(request) }
                .onFailure { log.warn("가공식품 리뷰 봇 생성 실패 productId={} attempt={}", product.id, attempt + 1, it) }
                .getOrNull()
            if (draft != null && ReviewBotContentGuard.isAcceptableForProcessedFood(draft.text) && ReviewBotContentGuard.fitsCard(draft.text, language)) {
                return draft.copy(spiciness = draft.spiciness ?: AXIS_FLOOR, sweetness = draft.sweetness ?: AXIS_FLOOR) to request
            }
            if (draft != null) log.warn("가공식품 리뷰 봇 생성물을 버린다(가드 또는 길이) productId={} attempt={}", product.id, attempt + 1)
        }
        return null
    }

    private fun save(
        product: ProcessedFoodProduct,
        bot: Member,
        request: ProcessedFoodReviewDraftRequest,
        draft: ProcessedFoodReviewDraft,
        photos: List<String>,
        ownerIds: Set<Long>,
        createdAt: LocalDateTime,
    ): Boolean =
        try {
            val review = reviewService.createBotProcessedFoodReview(bot.id, product.id, request.rating, draft.text, photos, ownerIds, draft.spiciness, draft.sweetness)
            val stamp = Timestamp.valueOf(createdAt)
            jdbc.update("UPDATE food_review SET created_at = ?, updated_at = ? WHERE id = ?", stamp, stamp, review.reviewId)
            true
        } catch (e: RuntimeException) {
            log.warn("가공식품 리뷰 봇 저장 실패 productId={} botId={}", product.id, bot.id, e)
            false
        }

    private fun targetCountOf(rank: Int, random: Random): Int =
        if (rank <= TOP_RANK) random.nextInt(TOP_MIN, TOP_MAX + 1) else random.nextInt(REST_MIN, REST_MAX + 1)

    private fun latestReviewAtOf(productIds: List<Long>): LocalDateTime? {
        if (productIds.isEmpty()) return null
        return jdbc.queryForObject(
            "SELECT MAX(created_at) FROM food_review WHERE status = 'ACTIVE' AND processed_food_id IN (${productIds.joinToString(",")})",
            LocalDateTime::class.java,
        )
    }

    private fun createdAtsOf(count: Int, latestExisting: LocalDateTime?, random: Random): List<LocalDateTime> {
        val now = LocalDateTime.now()
        val lower = maxOf(latestExisting ?: now.minusDays(SPREAD_DAYS), now.minusDays(SPREAD_DAYS))
        val span = Duration.between(lower, now).seconds.coerceAtLeast(1)
        return List(count) { inDaytime(lower.plusSeconds(random.nextLong(1, span + 1)), lower, now, random) }.sorted()
    }

    private fun inDaytime(at: LocalDateTime, lower: LocalDateTime, now: LocalDateTime, random: Random): LocalDateTime {
        val seoul = at.atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL)
        if (seoul.hour in FIRST_HOUR..LAST_HOUR) return at
        val shifted = seoul.withHour(random.nextInt(FIRST_HOUR, LAST_HOUR + 1)).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        return shifted.takeIf { it.isAfter(lower) && !it.isAfter(now) } ?: at
    }

    private companion object {
        const val MAX_LIMIT = 40
        const val AXIS_FLOOR = 1
        val LOCK_AT_MOST_FOR: Duration = Duration.ofMinutes(15)
        const val MAX_ATTEMPTS = 2
        const val MAX_INGREDIENTS = 8
        const val SEED_SPREAD = 1_000_003L
        const val TOP_RANK = 10
        const val TOP_MIN = 8
        const val TOP_MAX = 12
        const val REST_MIN = 3
        const val REST_MAX = 6
        const val SPREAD_DAYS = 28L
        const val FIRST_HOUR = 9
        const val LAST_HOUR = 21
        const val PHOTO_KEY_PREFIX = "processed:"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

@Schema(description = "가공식품 리뷰 봇 일괄 작성 결과")
data class ReviewBotProcessedFoodRunResult(
    @field:Schema(description = "true 면 생성만 하고 저장하지 않았다")
    val dryRun: Boolean,
    @field:Schema(description = "목표 수·봇·별점·스타일·작성일을 정한 seed — 요청에 없던 dryRun 이면 서버가 뽑은 값")
    val seed: Long,
    @field:Schema(description = "살펴본 대상 상품 수")
    val products: Int,
    @field:Schema(description = "이번 호출에서 생성을 시도한 리뷰 수(limit 이하)")
    val planned: Int,
    @field:Schema(description = "저장한 리뷰 수(dryRun 이면 0)")
    val written: Int,
    @field:Schema(description = "생성 실패·가드 거절·저장 실패가 있었던 상품 id")
    val failedProductIds: List<Long>,
    @field:Schema(description = "만든(dryRun 이면 만들 뻔한) 리뷰 미리보기")
    val items: List<ReviewBotProcessedFoodItem>,
)

@Schema(description = "가공식품 봇 리뷰 한 건(미리보기 또는 저장분)")
data class ReviewBotProcessedFoodItem(
    val productId: Long,
    val reportNo: String,
    val productName: String,
    val botId: Long,
    val language: String,
    val rating: Int,
    @field:Schema(nullable = true)
    val spiciness: Int?,
    @field:Schema(nullable = true)
    val sweetness: Int?,
    val content: String,
    @field:Schema(description = "작성일(서버 시각) — 최근 4주 09~21시 KST 사이로 흩는다")
    val createdAt: LocalDateTime,
)
