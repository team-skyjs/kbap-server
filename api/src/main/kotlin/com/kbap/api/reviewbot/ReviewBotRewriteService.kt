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

    fun rewrite(target: String, ratio: Double, dryRun: Boolean, limit: Int, seed: Long?): ReviewBotRewriteResult {
        if (target != environment) throw BusinessException(ErrorCode.REVIEW_BOT_ENVIRONMENT_MISMATCH)
        if (ratio !in 0.0..1.0 || limit !in 1..MAX_LIMIT) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val generator = generator ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val all = jdbc.query(
            "SELECT r.id, r.food_id, r.rating, r.content, r.language, m.country_code FROM food_review r JOIN member m ON m.id = r.member_id " +
                "WHERE r.status = 'ACTIVE' AND m.is_bot = 1 AND r.food_id IS NOT NULL AND r.content IS NOT NULL ORDER BY r.id",
        ) { rs, _ ->
            val language = ReviewBotCountries.languageNameOf(rs.getString(5)) ?: ReviewBotCountries.nativeLanguageOf(rs.getString(6))
            Candidate(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), language)
        }
        val candidates = all.filterNot { ReviewBotContentGuard.fitsCard(it.content, it.language) }
        val selected = candidates.shuffled(seed?.let(::Random) ?: Random.Default)
            .take((candidates.size * ratio).toInt()).take(limit).sortedBy { it.id }

        val failed = mutableListOf<Long>()
        val done = selected.mapNotNull { candidate ->
            val food = foods.findById(candidate.foodId).orElse(null)
            val after = food?.let { writer.generateAcceptable(it.id, generator, writer.draftRequestFor(it, candidate.language, candidate.rating)) }
            if (after == null || (!dryRun && !save(candidate, after))) {
                failed += candidate.id
                return@mapNotNull null
            }
            if (!dryRun) log.info("리뷰 봇 재작성 reviewId={} language={} beforeLength={} before={}", candidate.id, candidate.language, candidate.content.length, candidate.content)
            ReviewBotRewriteSample(candidate.id, candidate.language, candidate.content, after)
        }
        return ReviewBotRewriteResult(dryRun, candidates.size, selected.size, done.size, all.size - candidates.size, failed, done.take(SAMPLE_SIZE))
    }

    private fun save(candidate: Candidate, after: String): Boolean =
        writeTransaction.execute {
            val review = reviews.findById(candidate.id).orElse(null)
            if (review == null || review.content != candidate.content) {
                false
            } else {
                review.rewriteContent(after)
                true
            }
        } == true

    private data class Candidate(val id: Long, val foodId: Long, val rating: Int, val content: String, val language: String)

    private companion object {
        const val MAX_LIMIT = 200
        const val SAMPLE_SIZE = 5
    }
}

@Schema(description = "봇 리뷰 재작성 결과")
data class ReviewBotRewriteResult(
    @field:Schema(description = "true 면 생성만 하고 저장하지 않았다")
    val dryRun: Boolean,
    @field:Schema(description = "대상 수 — 봇 ACTIVE 리뷰 중 언어별 앱 카드 상한을 넘는 것")
    val candidates: Int,
    @field:Schema(description = "ratio·limit 로 뽑은 수")
    val selected: Int,
    @field:Schema(description = "다시 쓴(dryRun 이면 다시 쓸) 수")
    val rewritten: Int,
    @field:Schema(description = "이미 상한 안이라 대상에서 뺀 봇 리뷰 수")
    val alreadyShort: Int,
    @field:Schema(description = "생성 실패·금지어·상한 초과·그사이 수정돼 건너뛴 리뷰 id")
    val failedIds: List<Long>,
    @field:Schema(description = "앞 5건의 전·후 표본")
    val sample: List<ReviewBotRewriteSample>,
)

@Schema(description = "재작성 전·후 표본")
data class ReviewBotRewriteSample(val reviewId: Long, val language: String, val before: String, val after: String)
