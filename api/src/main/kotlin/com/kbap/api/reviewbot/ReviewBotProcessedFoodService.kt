package com.kbap.api.reviewbot

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class ReviewBotProcessedFoodService {
    fun run(target: String, dryRun: Boolean, limit: Int, ids: List<Long>?, topLines: Int?, seed: Long?): ReviewBotProcessedFoodRunResult =
        ReviewBotProcessedFoodRunResult(dryRun, seed ?: 0, 0, 0, 0, emptyList(), emptyList())
}

@Schema(description = "가공식품 리뷰 봇 일괄 작성 결과")
data class ReviewBotProcessedFoodRunResult(
    val dryRun: Boolean,
    val seed: Long,
    val products: Int,
    val planned: Int,
    val written: Int,
    val failedProductIds: List<Long>,
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
    val spiciness: Int?,
    val sweetness: Int?,
    val content: String,
    val createdAt: LocalDateTime,
)
