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
import kotlin.math.roundToInt
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

    fun run(target: String, dryRun: Boolean, limit: Int, ids: List<Long>?, topLines: Int?, seed: Long?, spreadDays: Int? = null): ReviewBotProcessedFoodRunResult {
        if (target != environment) throw BusinessException(ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH)
        if (limit !in 1..MAX_LIMIT || (!dryRun && seed == null) || (topLines != null && topLines < 1)) throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (spreadDays != null && spreadDays !in SPREAD_DAYS_RANGE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val generator = generator ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val bots = accountService.getBots().ifEmpty { throw BusinessException(ErrorCode.INVALID_REQUEST) }
        if (dryRun) return plan(dryRun, limit, ids, topLines, seed, generator, bots, spreadDays)
        val lock = lockProvider.lock(LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO)).orElse(null)
            ?: throw BusinessException(ErrorCode.REVIEW_BOT_BUSY)
        try {
            return plan(dryRun, limit, ids, topLines, seed, generator, bots, spreadDays)
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
        spreadDays: Int?,
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
        var skippedForDays = 0
        val dayCapacity = spreadDays?.let { availableDaysOf(it, LocalDateTime.now()) } ?: Int.MAX_VALUE
        for ((rank, product) in targets) {
            if (planned >= limit) break
            val members = candidateIndex.groupMemberIds(product.id)
            val reviewed = botReviewsOf(members)
            val random = Random(runSeed * SEED_SPREAD + product.id)
            val need = targetCountOf(rank, random) - reviewed.size
            val eligible = bots.filterNot { it.id in reviewed.keys }.sortedBy { it.id }.shuffled(random)
            var earlier = reviewed.values.lastOrNull()
            val wanted = maxOf(0, minOf(need, limit - planned))
            val writers = eligible.take(minOf(wanted, dayCapacity))
            skippedForDays += minOf(wanted, eligible.size) - writers.size
            val dates = spreadDays?.let { spreadDatesOf(writers.size, it, LocalDateTime.now(), random) } ?: createdAtsOf(writers.size, latestReviewAtOf(members), random)
            val pool = availableByKey[ReviewBotPhotoPool.keyOf(PHOTO_KEY_PREFIX + product.reportNo)].orEmpty()
            val usedPhotos = usedPhotosOf(members).toMutableSet()
            val photoSlots = writers.indices.shuffled(random).take((writers.size * PHOTO_SHARE).roundToInt()).toSet()
            for ((index, bot) in writers.withIndex()) {
                planned++
                val draft = draftFor(product, bot, earlier, random, generator)
                if (draft == null) {
                    failed += product.id
                    continue
                }
                val createdAt = dates[index]
                val photos = if (index in photoSlots) freshPhotos(pool, usedPhotos, random) else emptyList()
                if (!dryRun && !save(product, bot, draft.second, draft.first, photos, ownerIds, createdAt)) {
                    failed += product.id
                    continue
                }
                if (!dryRun) written++
                earlier = draft.first.text
                items += ReviewBotProcessedFoodItem(
                    product.id, product.reportNo, product.productName, bot.id, draft.second.language, draft.second.rating,
                    draft.first.spiciness, draft.first.sweetness, draft.first.text, createdAt, photos.size,
                )
            }
        }
        return ReviewBotProcessedFoodRunResult(dryRun, runSeed, targets.size, planned, written, failed.toList(), items, skippedForDays)
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

    private fun freshPhotos(pool: List<String>, used: MutableSet<String>, random: Random): List<String> {
        val remaining = pool.filterNot { it in used }
        if (remaining.isEmpty()) return emptyList()
        val picked = remaining.shuffled(random).take(random.nextInt(1, minOf(MAX_PHOTOS_PER_REVIEW, remaining.size) + 1))
        if (!photoService.isStillUsable(picked)) return emptyList()
        used += picked
        return picked
    }

    private fun usedPhotosOf(productIds: List<Long>): Set<String> {
        if (productIds.isEmpty()) return emptySet()
        return jdbc.queryForList(
            "SELECT CAST(image_refs AS CHAR) FROM food_review WHERE status = 'ACTIVE' AND image_refs IS NOT NULL AND processed_food_id IN (${productIds.joinToString(",")})",
            String::class.java,
        ).flatMap(::pathsIn).toSet()
    }

    fun dedupePhotos(target: String, dryRun: Boolean): ReviewBotPhotoDedupeResult {
        if (target != environment || environment == PROD) throw BusinessException(ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH)
        val rows = jdbc.query(
            "SELECT r.id, CAST(r.image_refs AS CHAR) FROM food_review r JOIN member m ON m.id = r.member_id " +
                "WHERE m.is_bot = 1 AND r.status = 'ACTIVE' AND r.target_type = 'PROCESSED_FOOD' AND r.image_refs IS NOT NULL ORDER BY r.created_at, r.id",
        ) { rs, _ -> rs.getLong(1) to pathsIn(rs.getString(2)) }
        val kept = mutableSetOf<String>()
        val changes = rows.mapNotNull { (id, paths) ->
            val keep = paths.filterNot { it in kept }.also { kept += it }
            if (keep.size == paths.size) null else ReviewBotPhotoDedupeChange(id, paths, keep)
        }
        if (!dryRun) {
            val lock = lockProvider.lock(LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO)).orElse(null)
                ?: throw BusinessException(ErrorCode.REVIEW_BOT_BUSY)
            try {
                changes.forEach { change ->
                    val refs = change.after.takeIf { it.isNotEmpty() }?.joinToString(",", "[", "]") { "\"$it\"" }
                    jdbc.update("UPDATE food_review SET image_refs = ?, updated_at = updated_at WHERE id = ?", refs, change.reviewId)
                }
            } finally {
                lock.unlock()
            }
        }
        return ReviewBotPhotoDedupeResult(dryRun, rows.size, changes.size, changes.sumOf { it.before.size - it.after.size }, changes.take(SAMPLE_SIZE))
    }

    private fun pathsIn(json: String?): List<String> = json?.let { PATH_IN_JSON.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()

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

    fun redistribute(target: String, days: Int, dryRun: Boolean, seed: Long): ReviewBotRedistributeResult {
        if (target != environment || environment == PROD) throw BusinessException(ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH)
        if (days !in SPREAD_DAYS_RANGE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val rows = jdbc.query(
            "SELECT r.id, r.processed_food_id, r.created_at FROM food_review r JOIN member m ON m.id = r.member_id " +
                "WHERE m.is_bot = 1 AND r.status = 'ACTIVE' AND r.target_type = 'PROCESSED_FOOD' ORDER BY r.id",
        ) { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toLocalDateTime()) }
        val now = LocalDateTime.now()
        val most = rows.groupingBy { it.second }.eachCount().values.maxOrNull() ?: 0
        if (most > availableDaysOf(days, now)) {
            val minDays = (days..SPREAD_DAYS_RANGE.last + 1).first { it > SPREAD_DAYS_RANGE.last || availableDaysOf(it, now) >= most }
            throw BusinessException(ErrorCode.INVALID_REQUEST, mapOf("minDays" to minDays, "maxReviewsPerProduct" to most))
        }
        val changes = rows.groupBy { it.second }.flatMap { (productId, reviews) ->
            val dates = spreadDatesOf(reviews.size, days, now, Random(seed * SEED_SPREAD + productId))
            reviews.zip(dates) { (id, _, before), after -> ReviewBotRedistributeChange(id, productId, before, after) }
        }
        if (!dryRun) {
            val lock = lockProvider.lock(LockConfiguration(Instant.now(), ReviewBotWriter.LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO)).orElse(null)
                ?: throw BusinessException(ErrorCode.REVIEW_BOT_BUSY)
            try {
                changes.forEach { change ->
                    val stamp = Timestamp.valueOf(change.after)
                    jdbc.update("UPDATE food_review SET created_at = ?, updated_at = ? WHERE id = ?", stamp, stamp, change.reviewId)
                }
            } finally {
                lock.unlock()
            }
        }
        return ReviewBotRedistributeResult(dryRun, days, rows.map { it.second }.distinct().size, changes.size, changes.take(SAMPLE_SIZE))
    }

    private fun todayUsable(now: LocalDateTime): Boolean = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL).hour > SPREAD_FIRST_HOUR

    private fun availableDaysOf(days: Int, now: LocalDateTime): Int = if (todayUsable(now)) days else maxOf(1, days - 1)

    private fun spreadDatesOf(count: Int, days: Int, now: LocalDateTime, random: Random): List<LocalDateTime> {
        if (count == 0) return emptyList()
        val nowSeoul = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL)
        val newest = if (todayUsable(now)) nowSeoul.toLocalDate() else nowSeoul.toLocalDate().minusDays(1)
        val span = availableDaysOf(days, now)
        return List(count) { index ->
            val from = index * span / count
            val until = maxOf(from + 1, (index + 1) * span / count)
            val day = newest.minusDays(random.nextInt(from, until).toLong())
            val lastHour = if (day == nowSeoul.toLocalDate()) nowSeoul.hour - 1 else SPREAD_LAST_HOUR
            day.atTime(random.nextInt(SPREAD_FIRST_HOUR, lastHour + 1), random.nextInt(60), random.nextInt(60))
                .atZone(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }.sorted()
    }

    private fun inDaytime(at: LocalDateTime, lower: LocalDateTime, now: LocalDateTime, random: Random): LocalDateTime {
        val seoul = at.atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL)
        if (seoul.hour in FIRST_HOUR..LAST_HOUR) return at
        val shifted = seoul.withHour(random.nextInt(FIRST_HOUR, LAST_HOUR + 1)).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        return shifted.takeIf { it.isAfter(lower) && !it.isAfter(now) } ?: at
    }

    private companion object {
        const val MAX_LIMIT = 40
        const val PHOTO_SHARE = 0.3
        const val PROD = "prod"
        const val MAX_PHOTOS_PER_REVIEW = 2
        const val SAMPLE_SIZE = 10
        val PATH_IN_JSON = Regex("\"([^\"]+)\"")
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
        val SPREAD_DAYS_RANGE = 1..90
        const val SPREAD_FIRST_HOUR = 8
        const val SPREAD_LAST_HOUR = 23
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
    @field:Schema(description = "spreadDays 의 쓸 수 있는 날 수(상품당 하루 1건)를 넘어 이번에 쓰지 않은 리뷰 수 — 더 긴 spreadDays 로 다시 부르면 채운다")
    val skippedForDays: Int = 0,
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
    @field:Schema(description = "작성일(서버 시각) — 상품마다 그 묶음 최신 리뷰 이후로 오름차순")
    val createdAt: LocalDateTime,
    @field:Schema(description = "붙인 사진 수(0~2) — 묶음 안 사진 1장당 리뷰 1건, 상품별 리뷰의 약 30% 에만")
    val imageCount: Int = 0,
)

@Schema(description = "봇 가공식품 리뷰 사진 중복 정리 결과")
data class ReviewBotPhotoDedupeResult(
    @field:Schema(description = "true 면 바꾸지 않고 계획만")
    val dryRun: Boolean,
    @field:Schema(description = "사진이 있는 봇 가공식품 리뷰 수")
    val reviewsScanned: Int,
    @field:Schema(description = "사진을 뗀(뗄) 리뷰 수 — 같은 경로를 먼저 쓴 리뷰가 있는 것")
    val reviewsChanged: Int,
    @field:Schema(description = "뗀(뗄) 사진 참조 수")
    val refsRemoved: Int,
    @field:Schema(description = "앞 10건")
    val samples: List<ReviewBotPhotoDedupeChange>,
)

@Schema(description = "사진 정리 한 건")
data class ReviewBotPhotoDedupeChange(val reviewId: Long, val before: List<String>, val after: List<String>)

data class ReviewBotRedistributeChange(val reviewId: Long, val processedFoodId: Long, val before: LocalDateTime, val after: LocalDateTime)

data class ReviewBotRedistributeResult(
    val dryRun: Boolean,
    val days: Int,
    val products: Int,
    val reviews: Int,
    val samples: List<ReviewBotRedistributeChange>,
)
