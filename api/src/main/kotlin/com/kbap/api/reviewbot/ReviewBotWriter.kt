package com.kbap.api.reviewbot

import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

@Service
class ReviewBotWriter(
    private val accountService: ReviewBotAccountService,
    private val reviewRepository: ReviewJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val ingredientRepository: IngredientJpaRepository,
    private val reviewService: ReviewService,
    private val generator: ReviewTextGenerator?,
    private val photoService: ReviewBotPhotoService,
    private val priorityList: ReviewBotPriorityList,
    private val photoPool: ReviewBotPhotoPool,
    @Value("\${kbap.review-bot.min-per-day:8}") private val minPerDay: Int,
    @Value("\${kbap.review-bot.max-per-day:12}") private val maxPerDay: Int,
    @Value("\${kbap.review-bot.time-budget:15m}") private val timeBudget: Duration,
) {
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    fun writeDue(now: ZonedDateTime, random: Random = Random.Default): ReviewBotTickSummary {
        val deadline = System.nanoTime() + timeBudget.toNanos()
        val generator = generator ?: run {
            log.warn("리뷰 봇 — 텍스트 생성기가 없어 건너뜁니다(kbap.llm.review.enabled=false)")
            return ReviewBotTickSummary.EMPTY
        }
        val bots = accountService.getBots().ifEmpty {
            log.warn("리뷰 봇 — 봇 계정이 없어 건너뜁니다(POST /api/admin/review-bots)")
            return ReviewBotTickSummary.EMPTY
        }
        val kst = now.withZoneSameInstant(SEOUL)
        val dayStart = kst.toLocalDate().atStartOfDay(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        val plan = ReviewBotDailyPlan.of(kst.toLocalDate(), minPerDay, maxPerDay, FIRST_HOUR, LAST_HOUR)
        val due = plan.dueUntil(kst.hour) - reviewRepository.countReviewBotReviewsSince(dayStart).toInt()
        if (due <= 0) return ReviewBotTickSummary.EMPTY
        val poolOwnerIds = photoPool.ownerIds()
        val pool = ReviewBotPhotoPool.PoolSnapshot(photoService.getInventory().available.groupBy { ReviewBotPhotoPool.keyOfPath(it)!! })
        var candidates = 0
        var skippedNoPool = 0
        var written = 0
        var afterReviewCount = 0L
        var afterFoodId = 0L
        var pages = 0
        val tried = mutableSetOf<Long>()
        val (priority, priorityWithoutPool) = priorityFoods(poolOwnerIds, dayStart).partition { pool.hasPool(it.koreanName) }
        priorityWithoutPool.forEach { if (tried.add(it.id)) skippedNoPool++ }
        for (food in priority) {
            if (written >= due) break
            if (System.nanoTime() >= deadline) {
                log.warn("리뷰 봇 — 시간 예산({})을 넘겨 이번 틱을 멈춥니다", timeBudget)
                break
            }
            if (!tried.add(food.id)) continue
            candidates++
            if (writeOne(food, bots, poolOwnerIds, pool.pathsFor(food.koreanName), generator, random) != null) written++
        }
        candidates@ while (written < due && pages++ < MAX_PAGES) {
            val page = reviewRepository.findReviewBotCandidatePage(dayStart, afterReviewCount, afterFoodId, due * CANDIDATE_FACTOR)
            if (page.isEmpty()) break
            val foods = foodRepository.findAllById(page.map { it.foodId }).associateBy { it.id }
            for (candidate in page) {
                if (written >= due) break@candidates
                if (System.nanoTime() >= deadline) {
                    log.warn("리뷰 봇 — 시간 예산({})을 넘겨 이번 틱을 멈춥니다", timeBudget)
                    break@candidates
                }
                if (!tried.add(candidate.foodId)) continue
                val food = foods[candidate.foodId] ?: continue
                if (!pool.hasPool(food.koreanName)) {
                    skippedNoPool++
                    continue
                }
                candidates++
                if (writeOne(food, bots, poolOwnerIds, pool.pathsFor(food.koreanName), generator, random) != null) written++
            }
            afterReviewCount = page.last().reviewCount
            afterFoodId = page.last().foodId
        }
        val summary = ReviewBotTickSummary(due = due, candidates = candidates, skippedNoPool = skippedNoPool, written = written, pages = pages)
        log.info(
            "리뷰 봇 작성 day={} hour={} target={} due={} candidates={} noPool={} written={} pages={} poolKeys={}",
            kst.toLocalDate(), kst.hour, plan.target, due, candidates, skippedNoPool, written, pages, pool.keyCount,
        )
        if (written == 0) {
            log.warn("리뷰 봇 — 이번 틱에 한 건도 못 썼습니다 due={} candidates={} noPool={} poolKeys={} (풀 등록: POST /api/admin/review-bots/images/sync)", due, candidates, skippedNoPool, pool.keyCount)
        }
        return summary
    }

    private fun priorityFoods(botAccountIds: Set<Long>, dayStart: LocalDateTime): List<Food> {
        val foods = foodRepository.findByKoreanNameIn(priorityList.koreanNames.toSet()).filter { it.isReady() }.associateBy { it.koreanName }
        if (foods.isEmpty() || botAccountIds.isEmpty()) return emptyList()
        val botReviews = reviewRepository.countBotReviewsByFoodIds(foods.values.map { it.id }, botAccountIds, dayStart).associateBy { it.foodId }
        return priorityList.koreanNames.mapNotNull { foods[it] }
            .filter { food -> botReviews[food.id].let { it == null || (it.botReviewCount < ReviewBotPriorityList.MAX_BOT_REVIEWS && it.todayCount == 0L) } }
    }

    private fun writeOne(food: Food, bots: List<Member>, poolOwnerIds: Set<Long>, pool: List<String>, generator: ReviewTextGenerator, random: Random): Long? {
        val foodId = food.id
        if (pool.isEmpty()) return null
        val reviewed = reviewRepository.findReviewerIdsOfFood(foodId, bots.map { it.id }).toSet()
        val bot = bots.filterNot { it.id in reviewed }.randomOrNull(random) ?: return null
        if (!food.isReady()) return null
        val available = pool
        val previous = reviewRepository.findFirstByFoodIdAndMemberIdInOrderByCreatedAtDesc(foodId, poolOwnerIds)?.imageRefs.orEmpty().toSet()
        val photos = ReviewBotPhotoPool.pickCombination(available, previous, random)
        val rating = ReviewBotCountries.rating(random)
        val earlier = reviewRepository.findFirstByFoodIdAndMemberIdInOrderByCreatedAtDesc(foodId, bots.map { it.id })?.content
        val request = draftRequestFor(food, ReviewBotCountries.languageFor(bot.countryCode, random), rating, random, earlier)
        val content = generateAcceptable(foodId, generator, request) ?: return null
        if (!photoService.isStillUsable(photos)) return null
        return runCatching {
            reviewService.createBotReview(bot.id, foodId, rating, content, photos, poolOwnerIds).reviewId
        }.onFailure { log.warn("리뷰 봇 저장 실패 foodId={} botId={}", foodId, bot.id, it) }.getOrNull()
    }

    fun draftRequestFor(food: Food, language: String, rating: Int, random: Random = Random.Default, avoidSimilarTo: String? = null): ReviewDraftRequest =
        ReviewDraftRequest(
            foodName = food.displayName(LanguageCode.EN),
            description = food.description(LanguageCode.EN),
            ingredients = ingredientNamesOf(food.ingredientsByProbability().map { it.code }),
            language = language,
            rating = rating,
            maxCharacters = ReviewBotContentGuard.maxLengthFor(language),
            style = ReviewBotStyles.pick(random),
            sentences = ReviewBotStyles.sentences(random),
            avoidSimilarTo = avoidSimilarTo,
        )

    fun generateAcceptable(foodId: Long, generator: ReviewTextGenerator, request: ReviewDraftRequest): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val text = runCatching { generator.generate(request) }
                .onFailure { log.warn("리뷰 봇 생성 실패 foodId={} food={} attempt={}", foodId, request.foodName, attempt + 1, it) }
                .getOrNull()
            if (text != null && ReviewBotContentGuard.isAcceptable(text) && ReviewBotContentGuard.fitsCard(text, request.language)) return text
            if (text != null) {
                log.warn(
                    "리뷰 봇 생성물을 버린다(금지어 또는 앱 카드 상한 {}자 초과) foodId={} food={} attempt={} length={}",
                    request.maxCharacters, foodId, request.foodName, attempt + 1, text.trim().let { it.codePointCount(0, it.length) },
                )
            }
        }
        log.warn("리뷰 봇 — 이 음식은 이번 틱에서 건너뜁니다(초안 {}회 모두 생성 실패·금지어·길이 초과) foodId={} food={}", MAX_ATTEMPTS, foodId, request.foodName)
        return null
    }

    private fun ingredientNamesOf(codes: List<String>): List<String> {
        val known = codes.mapNotNull { code -> IngredientCode.entries.firstOrNull { it.name == code } }.toSet()
        if (known.isEmpty()) return emptyList()
        val byCode = ingredientRepository.findByCodeIn(known).associateBy { it.code }
        return known.mapNotNull { byCode[it]?.displayName(LanguageCode.EN) }.take(MAX_INGREDIENTS)
    }

    companion object {
        const val LOCK_NAME = "review-bot-writer"
        const val LOCK_AT_MOST_FOR = "PT20M"
        const val FIRST_HOUR = 9
        const val LAST_HOUR = 22
        private const val MAX_ATTEMPTS = 2
        private const val CANDIDATE_FACTOR = 3
        private const val MAX_PAGES = 5
        private const val MAX_INGREDIENTS = 6
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val log = LoggerFactory.getLogger(ReviewBotWriter::class.java)
    }
}

data class ReviewBotTickSummary(
    val due: Int,
    val candidates: Int,
    val skippedNoPool: Int,
    val written: Int,
    val pages: Int,
) {
    companion object {
        val EMPTY = ReviewBotTickSummary(0, 0, 0, 0, 0)
    }
}
