package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.port.llm.ReviewTextGenerator
import io.swagger.v3.oas.annotations.media.Schema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.random.Random

@Service
class ReviewBotRewriteService(
    private val jdbc: JdbcTemplate,
    private val reviews: ReviewJpaRepository,
    private val foods: FoodJpaRepository,
    private val writer: ReviewBotWriter,
    private val generator: ReviewTextGenerator?,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val writeTransaction = TransactionTemplate(transactionManager)

    fun rewrite(target: String, ratio: Double, dryRun: Boolean, limit: Int, seed: Long?, afterId: Long? = null): ReviewBotRewriteResult {
        if (target != environment) throw BusinessException(ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH)
        if (ratio !in 0.0..1.0 || limit !in 1..MAX_LIMIT || (!dryRun && seed == null)) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val generator = generator ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val sampleSeed = seed ?: Random.Default.nextLong()
        val all = jdbc.query(
            "SELECT r.id, r.food_id, r.rating, r.content, r.language, m.country_code FROM food_review r JOIN member m ON m.id = r.member_id " +
                "WHERE r.status = 'ACTIVE' AND m.is_bot = 1 AND r.food_id IS NOT NULL AND r.content IS NOT NULL ORDER BY r.id",
        ) { rs, _ ->
            val language = ReviewBotCountries.languageNameOf(rs.getString(5)) ?: ReviewBotCountries.nativeLanguageOf(rs.getString(6))
            Candidate(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), language)
        }
        val sampled = all.filter { isSampled(sampleSeed, it.id, ratio) }
        val candidates = sampled.filterNot { ReviewBotContentGuard.fitsCard(it.content, it.language) }
        val remaining = candidates.filter { afterId == null || it.id > afterId }
        val selected = remaining.take(limit)
        val nextAfterId = selected.lastOrNull()?.id?.takeIf { remaining.size > selected.size }
        val latestShort = all.filter { ReviewBotContentGuard.fitsCard(it.content, it.language) }
            .groupBy { it.foodId }.mapValues { (_, reviews) -> reviews.maxBy { it.id }.content }.toMutableMap()

        val failed = mutableListOf<Long>()
        val done = selected.mapNotNull { candidate ->
            val after = try {
                foods.findById(candidate.foodId).orElse(null)
                    ?.let { food ->
                        val request = writer.draftRequestFor(food, candidate.language, candidate.rating, styleRandom(sampleSeed, candidate.id, 0), latestShort[candidate.foodId])
                        writer.generateAcceptable(food.id, generator) { attempt ->
                            if (attempt == 0) request else ReviewBotStyles.restyle(request, styleRandom(sampleSeed, candidate.id, attempt))
                        }
                    }
                    ?.takeIf { dryRun || save(candidate, it) }
            } catch (e: RuntimeException) {
                log.warn("리뷰 봇 재작성 — 이 리뷰만 건너뜀 reviewId={}", candidate.id, e)
                null
            }
            if (after == null) {
                failed += candidate.id
                return@mapNotNull null
            }
            latestShort[candidate.foodId] = after
            if (!dryRun) log.info("리뷰 봇 재작성 reviewId={} language={} beforeLength={} before={}", candidate.id, candidate.language, candidate.content.length, candidate.content)
            ReviewBotRewriteSample(candidate.id, candidate.language, candidate.content, after)
        }
        return ReviewBotRewriteResult(dryRun, nextAfterId, sampleSeed, sampled.size, candidates.size, selected.size, done.size, sampled.size - candidates.size, failed, done.take(SAMPLE_SIZE))
    }

    private fun save(candidate: Candidate, after: String): Boolean =
        writeTransaction.execute {
            val review = reviews.findByIdForUpdate(candidate.id)
            if (review == null || review.content != candidate.content) {
                false
            } else {
                review.rewriteContent(after)
                true
            }
        } == true

    private fun isSampled(seed: Long, reviewId: Long, ratio: Double): Boolean = Random(seed * SAMPLE_SPREAD + reviewId).nextDouble() < ratio

    private fun styleRandom(seed: Long, reviewId: Long, attempt: Int): Random =
        Random(((seed * SAMPLE_SPREAD + reviewId) xor STYLE_SALT) + attempt * ATTEMPT_SPREAD)

    private data class Candidate(val id: Long, val foodId: Long, val rating: Int, val content: String, val language: String)

    private companion object {
        const val MAX_LIMIT = 40
        const val SAMPLE_SPREAD = 1_000_003L
        const val STYLE_SALT = 0x5DEECE66DL
        const val ATTEMPT_SPREAD = 0x9E3779B97F4AL
        const val SAMPLE_SIZE = 5
    }
}

@Schema(description = "봇 리뷰 재작성 결과")
data class ReviewBotRewriteResult(
    @field:Schema(description = "true 면 생성만 하고 저장하지 않았다")
    val dryRun: Boolean,
    @field:Schema(description = "다음 호출에 afterId 로 넘길 커서 — 이번에 본(실패 포함) 마지막 리뷰 id, 남은 대상이 없으면 null", nullable = true)
    val nextAfterId: Long?,
    @field:Schema(description = "표본 seed — 요청에 없던 dryRun 이면 서버가 뽑은 값(같은 표본으로 실행할 때 그대로 넘긴다)")
    val seed: Long,
    @field:Schema(description = "표본 크기 — 봇 ACTIVE 음식 리뷰 중 (seed, 리뷰 id) 로 정해진 ratio 몫. 몇 번 불러도 같다")
    val sampled: Int,
    @field:Schema(description = "남은 대상 수 — 표본 중 아직 언어별 앱 카드 상한을 넘는 것(0 이면 끝)")
    val candidates: Int,
    @field:Schema(description = "이번 호출에서 처리한 수(남은 대상 중 id 순 limit 개)")
    val selected: Int,
    @field:Schema(description = "다시 쓴(dryRun 이면 다시 쓸) 수")
    val rewritten: Int,
    @field:Schema(description = "표본 중 이미 상한 안인 수(원래 짧았거나 이전 호출에서 다시 쓴 것)")
    val alreadyShort: Int,
    @field:Schema(description = "생성 실패·금지어·상한 초과·그사이 수정돼 건너뛴 리뷰 id")
    val failedIds: List<Long>,
    @field:Schema(description = "앞 5건의 전·후 표본")
    val sample: List<ReviewBotRewriteSample>,
)

@Schema(description = "재작성 전·후 표본")
data class ReviewBotRewriteSample(val reviewId: Long, val language: String, val before: String, val after: String)
