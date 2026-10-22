package com.kbap.api.processedfood

import com.kbap.common.domain.ingredient.label.AllergenDeclaration
import com.kbap.common.domain.ingredient.label.IngredientLabelMatch
import com.kbap.common.domain.ingredient.label.IngredientLabelReading
import com.kbap.common.domain.ingredient.label.IngredientLabelSource
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.review.ProcessedFoodRatingAggregate
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "가공식품 검색 결과 항목")
data class ProcessedFoodSummaryResponse(
    @field:Schema(description = "가공식품 id", example = "1201")
    val id: Long,
    @field:Schema(description = "품목보고번호 — 이름 스캔 확정(scan/confirm)의 reportNo 로 그대로 보낸다", example = "19700001001")
    val reportNo: String,
    @field:Schema(description = "제품명(표시 원문)", example = "새우깡")
    val productName: String,
    @field:Schema(description = "제조사 — 제조원·판매원의 업체명(공장명 제외), 둘 다 미상이면 식품영양성분DB 보완값, 그래도 없으면 null", example = "농심", nullable = true)
    val maker: String?,
    @field:Schema(description = "식품 유형", example = "과자", nullable = true)
    val foodType: String?,
    @field:Schema(description = "제품 이미지 URL(HACCP http 원본은 https 로 바꿔 내려간다) — 없으면 null(플레이스홀더는 앱)", nullable = true)
    val imageUrl: String?,
    @field:Schema(description = "리뷰 수 — 검색 결과에서만 채운다(없으면 0). 이름 스캔 후보는 집계하지 않아 null", example = "12", nullable = true)
    val reviewCount: Long? = null,
    @field:Schema(description = "평균 별점 — 리뷰가 없거나 집계하지 않은 카드(이름 스캔 후보)는 null", example = "4.2", nullable = true)
    val avgRating: Double? = null,
) {
    companion object {
        fun from(product: ProcessedFoodProduct) =
            ProcessedFoodSummaryResponse(product.id, product.reportNo, product.productName, product.displayMaker(), product.foodType, ProcessedFoodProduct.secureImageUrl(product.imageUrl))

        fun withReviews(product: ProcessedFoodProduct, rating: ProcessedFoodRatingAggregate?) =
            from(product).copy(reviewCount = rating?.reviewCount ?: 0, avgRating = rating?.average)
    }
}

@Schema(description = "가공식품 리뷰 요약")
data class ProcessedFoodReviewSummaryResponse(
    @field:Schema(description = "리뷰 수", example = "12")
    val count: Long,
    @field:Schema(description = "평균 별점 — 리뷰가 없으면 null", example = "4.2", nullable = true)
    val avgRating: Double?,
)

@Schema(description = "알레르기 판독 근거 — DECLARED: 알레르기 표시란(의무 표시)이 있다 · INGREDIENTS_ONLY: 표시란이 '알수없음'·빈 값이라 원재료 원문으로만 판독 · NONE: 둘 다 없다")
enum class ProcessedFoodLabelSource { DECLARED, INGREDIENTS_ONLY, NONE }

@Schema(description = "가공식품 상세")
data class ProcessedFoodDetailResponse(
    val id: Long,
    @field:Schema(description = "품목보고번호", example = "19700001001")
    val reportNo: String,
    val productName: String,
    @field:Schema(nullable = true)
    val maker: String?,
    @field:Schema(nullable = true)
    val foodType: String?,
    @field:Schema(description = "내용량 원문", nullable = true)
    val capacity: String?,
    @field:Schema(description = "원재료 원문 그대로", nullable = true)
    val rawMaterials: String?,
    @field:Schema(description = "알레르기 표시 원문 그대로('알수없음' 포함)", nullable = true)
    val allergens: String?,
    val labelSource: ProcessedFoodLabelSource,
    @field:Schema(description = "검출된 회피 재료 — 출처(알레르기 표시 우선)와 근거 토큰. 검출되지 않은 재료는 '없음'이 아니라 미확인이다")
    val detections: List<ProcessedFoodDetectionResponse>,
    @field:Schema(description = "81종 어디에도 대응하지 못한 원문 토큰(원재료·알레르기 표시) — 앱이 '확인 필요'로 보여 준다. 검출 없음이 안전을 뜻하지 않는다", example = "[\"미국산\", \"버터향\"]")
    val unmapped: List<String>,
    @field:Schema(description = "조회 회원의 회피 재료별 대조 — DETECTED 또는 UNCONFIRMED 뿐(없음·안전 판정 없음). 비회원은 null", nullable = true)
    val avoidance: List<ProcessedFoodAvoidanceResponse>?,
    val source: ProcessedFoodSourceResponse,
    @field:Schema(description = "제품 이미지 — 없으면 null", nullable = true)
    val image: ProcessedFoodImageResponse?,
    @field:Schema(description = "리뷰 요약(수·평균 별점)")
    val reviewSummary: ProcessedFoodReviewSummaryResponse,
) {
    companion object {
        fun of(product: ProcessedFoodProduct, reading: IngredientLabelReading, avoidance: Avoidance?, rating: ProcessedFoodRatingAggregate?): ProcessedFoodDetailResponse =
            ProcessedFoodDetailResponse(
                id = product.id,
                reportNo = product.reportNo,
                productName = product.productName,
                maker = product.displayMaker(),
                foodType = product.foodType,
                capacity = product.capacity,
                rawMaterials = product.rawMaterials,
                allergens = product.allergens,
                labelSource = when {
                    reading.declaration == AllergenDeclaration.LISTED -> ProcessedFoodLabelSource.DECLARED
                    !product.rawMaterials.isNullOrBlank() -> ProcessedFoodLabelSource.INGREDIENTS_ONLY
                    else -> ProcessedFoodLabelSource.NONE
                },
                detections = reading.detections.map { ProcessedFoodDetectionResponse(it.code, it.source, it.evidence) },
                unmapped = reading.unmappedTokens,
                avoidance = avoidance?.let { avoidanceOf(it, reading) },
                source = ProcessedFoodSourceResponse(product.source, "processedFood.source.${product.source.lowercase()}", product.collectedAt),
                image = ProcessedFoodProduct.secureImageUrl(product.imageUrl)?.let { ProcessedFoodImageResponse(it, product.source, "processedFood.image.haccp") },
                reviewSummary = ProcessedFoodReviewSummaryResponse(rating?.reviewCount ?: 0, rating?.average),
            )

        fun avoidanceOf(avoidance: Avoidance, reading: IngredientLabelReading): List<ProcessedFoodAvoidanceResponse> =
            avoidance.chosen.sortedBy { it.ordinal }.map { chosen ->
                val covered = avoidance.codeNamesCoveredBy(chosen)
                val matched = reading.detections.filter { it.code.name in covered }
                ProcessedFoodAvoidanceResponse(
                    code = chosen,
                    match = if (matched.isEmpty()) IngredientLabelMatch.UNCONFIRMED else IngredientLabelMatch.DETECTED,
                    matchedCodes = matched.map { it.code },
                    evidence = matched.map { it.evidence },
                )
            }
    }
}

data class ProcessedFoodDetectionResponse(
    val code: IngredientCode,
    val source: IngredientLabelSource,
    @field:Schema(description = "검출 근거가 된 원문 토큰", example = "소맥분")
    val evidence: String,
)

data class ProcessedFoodAvoidanceResponse(
    @field:Schema(description = "회원이 고른 회피 재료")
    val code: IngredientCode,
    @field:Schema(description = "DETECTED: 이 재료(또는 함의 재료)가 검출됨 · UNCONFIRMED: 검출되지 않음 — 들어 있지 않다는 뜻이 아니다")
    val match: IngredientLabelMatch,
    @field:Schema(description = "이 회피 재료에 걸린 검출 코드(함의 포함, 예: MILK 회피 → BUTTER)")
    val matchedCodes: List<IngredientCode>,
    val evidence: List<String>,
)

data class ProcessedFoodSourceResponse(
    @field:Schema(description = "데이터 출처 — HACCP(식품안전정보원 HACCP 제품정보) 또는 C002(식약처 품목제조보고)", example = "HACCP", allowableValues = ["HACCP", "C002"])
    val provider: String,
    @field:Schema(description = "출처 고지 문구 키 — 앱이 언어별 문구로 바꾼다. processedFood.source.haccp 또는 processedFood.source.c002", example = "processedFood.source.haccp")
    val attributionKey: String,
    @field:Schema(description = "이 제품을 처음 수집한 시각. 원본 내용 변경 시각은 아직 기록하지 않아 내려가지 않는다(미러 확인 시각을 갱신일로 보이지 않기 위해)")
    val collectedAt: LocalDateTime,
)

data class ProcessedFoodImageResponse(
    val url: String,
    @field:Schema(description = "이미지 출처", example = "HACCP")
    val source: String,
    @field:Schema(description = "이미지 저작 고지 문구 키", example = "processedFood.image.haccp")
    val attributionKey: String,
)
