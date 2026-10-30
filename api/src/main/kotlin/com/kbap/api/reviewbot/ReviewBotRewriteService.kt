package com.kbap.api.reviewbot

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service

@Service
class ReviewBotRewriteService {
    fun rewrite(target: String, ratio: Double, dryRun: Boolean, limit: Int, seed: Long?): ReviewBotRewriteResult =
        ReviewBotRewriteResult(dryRun, 0, 0, 0, 0, emptyList(), emptyList())
}

@Schema(description = "봇 리뷰 재작성 결과")
data class ReviewBotRewriteResult(
    val dryRun: Boolean,
    val candidates: Int,
    val selected: Int,
    val rewritten: Int,
    val alreadyShort: Int,
    val failedIds: List<Long>,
    val sample: List<ReviewBotRewriteSample>,
)

@Schema(description = "재작성 전·후 표본")
data class ReviewBotRewriteSample(val reviewId: Long, val language: String, val before: String, val after: String)
