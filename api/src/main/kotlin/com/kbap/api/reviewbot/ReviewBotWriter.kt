package com.kbap.api.reviewbot

import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
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
    @Value("\${kbap.review-bot.min-per-day:8}") private val minPerDay: Int,
    @Value("\${kbap.review-bot.max-per-day:12}") private val maxPerDay: Int,
) {
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    fun writeDue(now: ZonedDateTime, random: Random = Random.Default) {
        val generator = generator
            ?: return log.warn("리뷰 봇 — 텍스트 생성기가 없어 건너뜁니다(kbap.llm.review.enabled=false)")
        val bots = accountService.getBots().ifEmpty {
            return log.warn("리뷰 봇 — 봇 계정이 없어 건너뜁니다(POST /api/admin/review-bots)")
        }
        val kst = now.withZoneSameInstant(SEOUL)
        val dayStart = kst.toLocalDate().atStartOfDay(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        val plan = ReviewBotDailyPlan.of(kst.toLocalDate(), minPerDay, maxPerDay, FIRST_HOUR, LAST_HOUR)
        val due = plan.dueUntil(kst.hour) - reviewRepository.countReviewBotReviewsSince(dayStart).toInt()
        if (due <= 0) return
        val written = reviewRepository.findReviewBotTargetFoodIds(dayStart, due * CANDIDATE_FACTOR)
            .asSequence()
            .mapNotNull { foodId -> writeOne(foodId, bots, generator, random) }
            .take(due)
            .count()
        log.info("리뷰 봇 작성 day={} hour={} target={} due={} written={}", kst.toLocalDate(), kst.hour, plan.target, due, written)
    }

    private fun writeOne(foodId: Long, bots: List<Member>, generator: ReviewTextGenerator, random: Random): Long? {
        val reviewed = reviewRepository.findReviewerIdsOfFood(foodId, bots.map { it.id }).toSet()
        val bot = bots.filterNot { it.id in reviewed }.randomOrNull(random) ?: return null
        val food = foodRepository.findById(foodId).orElse(null)?.takeIf { it.isReady() } ?: return null
        val rating = ReviewBotCountries.rating(random)
        val request = ReviewDraftRequest(
            foodName = food.displayName(LanguageCode.EN),
            description = food.description(LanguageCode.EN),
            ingredients = ingredientNamesOf(food.ingredientsByProbability().map { it.code }),
            language = ReviewBotCountries.languageFor(bot.countryCode, random),
            rating = rating,
        )
        val content = generateAcceptable(generator, request) ?: return null
        return runCatching {
            reviewService.createReview(bot.id, foodId, rating, null, null, content, null, null).reviewId
        }.onFailure { log.warn("리뷰 봇 저장 실패 foodId={} botId={}", foodId, bot.id, it) }.getOrNull()
    }

    private fun generateAcceptable(generator: ReviewTextGenerator, request: ReviewDraftRequest): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val text = runCatching { generator.generate(request) }
                .onFailure { log.warn("리뷰 봇 생성 실패 food={} attempt={}", request.foodName, attempt + 1, it) }
                .getOrNull()
            if (text != null && ReviewBotContentGuard.isAcceptable(text)) return text
            if (text != null) log.info("리뷰 봇 생성물이 금지어 필터에 걸려 버린다 food={} attempt={}", request.foodName, attempt + 1)
        }
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
        const val FIRST_HOUR = 9
        const val LAST_HOUR = 22
        private const val MAX_ATTEMPTS = 2
        private const val CANDIDATE_FACTOR = 3
        private const val MAX_INGREDIENTS = 6
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val log = LoggerFactory.getLogger(ReviewBotWriter::class.java)
    }
}
