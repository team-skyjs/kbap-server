package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.ingredient.label.IngredientLabelMatch
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.ingredient.label.IngredientLabelReading
import com.kbap.common.domain.ingredient.label.IngredientLabelSource
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.review.ProcessedFoodRatingAggregate
import com.kbap.common.domain.review.ProcessedFoodReviewAxes
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
    @field:Schema(description = "리뷰 수 — 목록·검색·이름 스캔 후보에서 채운다(없으면 0)", example = "12", nullable = true)
    val reviewCount: Long? = null,
    @field:Schema(description = "평균 별점 — 리뷰가 없으면 null", example = "4.2", nullable = true)
    val avgRating: Double? = null,
    @field:Schema(description = "조회 회원의 내 판정(상세와 같은 규칙) — 비회원·회피 재료 0개·원재료와 알레르기 표시가 모두 없으면 null", nullable = true)
    val verdict: ProcessedFoodVerdict? = null,
    @field:Schema(description = "표시 이름 — 요청 lang 의 저장된 번역(없으면 한국어 원문, 빠진 번역은 뒤에서 채운다)", example = "Banana Flavored Milk")
    val name: String = productName,
    @field:Schema(description = "한국어 원문 제품명", example = "빙그레 바나나맛 우유")
    val nameKo: String = productName,
    @field:Schema(description = "유형 칩 — 어디에도 안 맞으면 null", nullable = true)
    val type: ProcessedFoodType? = ProcessedFoodType.of(foodType),
    @field:Schema(description = "평점 — 목록·검색·이름 스캔 후보에서 채운다(리뷰 없으면 average null·count 0)", nullable = true)
    val rating: ProcessedFoodRatingResponse? = null,
    @field:Schema(description = "표시 정보 유무 — 원재료 또는 알레르기 표시가 있으면 true(상세 labelSource 가 NONE 이 아니면 true, 같은 규칙). false 면 상세는 '확인 못 함'이라 카드에 Safe 리본을 붙이지 않는다", example = "true")
    val labelKnown: Boolean,
) {
    companion object {
        fun from(product: ProcessedFoodProduct, avoidance: Avoidance? = null) =
            ProcessedFoodSummaryResponse(
                product.id, product.reportNo, product.productName, product.displayMaker(), product.foodType, product.displayImageUrl(),
                verdict = avoidance?.let { ProcessedFoodJudge.judge(product.allergens, product.rawMaterials, it).verdict },
                labelKnown = ProcessedFoodLabelSource.of(product.allergens, product.rawMaterials) != ProcessedFoodLabelSource.NONE,
            )

        fun withReviews(product: ProcessedFoodProduct, rating: ProcessedFoodRatingAggregate?, avoidance: Avoidance? = null) =
            from(product, avoidance).copy(
                reviewCount = rating?.reviewCount ?: 0,
                avgRating = rating?.average,
                rating = ProcessedFoodRatingResponse(rating?.average, rating?.reviewCount ?: 0),
            )
    }
}

@Schema(description = "번역문과 한국어 원문")
data class ProcessedFoodLocalizedText(
    @field:Schema(description = "요청 lang 번역 — 한국어 요청·번역 실패·일일 상한 초과면 원문과 같다")
    val translated: String,
    @field:Schema(description = "한국어 원문")
    val original: String,
)

@Schema(description = "가공식품 평점")
data class ProcessedFoodRatingResponse(
    @field:Schema(description = "평균 별점 — 리뷰가 없으면 null", example = "4.5", nullable = true)
    val average: Double?,
    @field:Schema(description = "리뷰 수", example = "2")
    val count: Long,
)

@Schema(description = "가공식품 리뷰 요약")
data class ProcessedFoodReviewSummaryResponse(
    @field:Schema(description = "리뷰 수", example = "12")
    val count: Long,
    @field:Schema(description = "평균 별점 — 리뷰가 없으면 null", example = "4.2", nullable = true)
    val avgRating: Double?,
    @field:Schema(description = "종합 — 별점 평균(맵기·당도는 세기라 넣지 않는다), count 는 리뷰 수")
    val overall: ProcessedFoodRatingResponse,
    @field:Schema(description = "맛 — 별점 평균, count 는 리뷰 수")
    val taste: ProcessedFoodRatingResponse,
    @field:Schema(description = "맵기 — 1~5 로 입력된 리뷰만 분모(미입력 제외)")
    val spiciness: ProcessedFoodRatingResponse,
    @field:Schema(description = "당도 — 1~5 로 입력된 리뷰만 분모(미입력 제외)")
    val sweetness: ProcessedFoodRatingResponse,
) {
    companion object {
        fun of(axes: ProcessedFoodReviewAxes): ProcessedFoodReviewSummaryResponse {
            val taste = axes.taste?.takeIf { axes.reviewCount > 0 }
            return ProcessedFoodReviewSummaryResponse(
                count = axes.reviewCount,
                avgRating = taste,
                overall = ProcessedFoodRatingResponse(taste?.roundToFirstDecimal(), axes.reviewCount),
                taste = ProcessedFoodRatingResponse(taste?.roundToFirstDecimal(), axes.reviewCount),
                spiciness = ProcessedFoodRatingResponse(axes.spicinessAverage?.roundToFirstDecimal(), axes.spicinessCount),
                sweetness = ProcessedFoodRatingResponse(axes.sweetnessAverage?.roundToFirstDecimal(), axes.sweetnessCount),
            )
        }

        private fun Double.roundToFirstDecimal(): Double = Math.round(this * 10) / 10.0
    }
}

@Schema(description = "알레르기 판독 근거 — DECLARED: 알레르기 표시란(의무 표시)이 있다 · INGREDIENTS_ONLY: 표시란이 '알수없음'·빈 값이라 원재료 원문으로만 판독 · NONE: 둘 다 없다")
enum class ProcessedFoodLabelSource {
    DECLARED,
    INGREDIENTS_ONLY,
    NONE,
    ;

    companion object {
        fun of(allergens: String?, rawMaterials: String?): ProcessedFoodLabelSource = when {
            IngredientLabelReader.declares(ProcessedFoodLabelText.splitMayContain(allergens, ingredientField = false).first) -> DECLARED
            !IngredientLabelReader.isRawNotProvided(rawMaterials) -> INGREDIENTS_ONLY
            else -> NONE
        }
    }
}

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
    @field:Schema(description = "폐기(KB-866) — 81종 밖 원재료는 판정에서 빠져 항상 빈 배열이다. 앱 호환을 위해 남겨 둔다", deprecated = true)
    val unmapped: List<String>,
    @field:Schema(description = "조회 회원의 회피 재료별 대조 — DETECTED 또는 UNCONFIRMED 뿐(없음·안전 판정 없음). 비회원은 null", nullable = true)
    val avoidance: List<ProcessedFoodAvoidanceResponse>?,
    val source: ProcessedFoodSourceResponse,
    @field:Schema(description = "제품 이미지 — 없으면 null", nullable = true)
    val image: ProcessedFoodImageResponse?,
    @field:Schema(description = "리뷰 요약(수·평균 별점)")
    val reviewSummary: ProcessedFoodReviewSummaryResponse,
    @field:Schema(description = "내 판정 — AVOID·CAUTION·SAFE·SAFE_WITH_NOTE. 비회원·회피 재료 0개·원재료와 알레르기 표시가 모두 없으면 null", nullable = true)
    val verdict: ProcessedFoodVerdict?,
    @field:Schema(description = "원재료·알레르기 표시에 걸린 내 회피 재료와 근거 토큰(AVOID 근거)")
    val avoidMatches: List<ProcessedFoodVerdictMatch>,
    @field:Schema(description = "혼입 가능 표기에만 걸린 내 회피 재료와 그 문구(CAUTION 근거)")
    val mayContainMatches: List<ProcessedFoodVerdictMatch>,
    @field:Schema(description = "혼입 가능 문장(같은 제조시설·혼입 등)에서 읽힌 재료 전부 — 회원 여부·회피와 무관(성분표 분석 결과의 mayContain 과 같은 형태). detections 에는 들어가지 않는다")
    val mayContain: List<ProcessedFoodMayContainResponse>,
    @field:Schema(description = "내용 불명 원재료(향료·혼합제제·가공품·복합원재료 등 서버 사전) — SAFE_WITH_NOTE 근거", example = "[\"합성향료\"]")
    val unclearIngredients: List<String>,
    @field:Schema(description = "unclearIngredients 에 걸린 서버 사전 용어(혼합제제·가공품·복합원재료·복합조미식품·시즈닝·조미료·양념·믹스·베이스)와 요청 lang 라벨 — 사전 순서, 없으면 빈 배열. 원문 토큰은 unclearIngredients 그대로")
    val unclearIngredientLabels: List<UnclearIngredientLabel> = emptyList(),
    @field:Schema(description = "한국 표시 의무 알레르기(식품표시광고법 시행규칙 별표 2) 중 들어 있는 코드 — 혼입 가능 표기는 빼고. 비회원·회피 0개 화면용")
    val majorAllergens: List<IngredientCode>,
    @field:Schema(description = "표시 이름 — 요청 lang 번역(실패·상한 초과면 한국어 원문)", example = "Banana Flavored Milk")
    val name: String = productName,
    @field:Schema(description = "한국어 원문 제품명", example = "빙그레 바나나맛 우유")
    val nameKo: String = productName,
    @field:Schema(description = "원재료 — translated(요청 lang, 실패 시 원문)·original(한국어). 원재료 원문이 없거나 '알수없음' 등이면 null", nullable = true)
    val ingredients: ProcessedFoodLocalizedText? = null,
    @field:Schema(description = "알레르기 표시 — translated·original. 표시가 없거나 '알수없음' 등이면 null", nullable = true)
    val allergenStatement: ProcessedFoodLocalizedText? = null,
) {
    companion object {
        fun of(product: ProcessedFoodProduct, avoidance: Avoidance?, axes: ProcessedFoodReviewAxes): ProcessedFoodDetailResponse {
            val judgement = ProcessedFoodJudge.judge(product.allergens, product.rawMaterials, avoidance)
            val reading = judgement.reading
            return ProcessedFoodDetailResponse(
                id = product.id,
                reportNo = product.reportNo,
                productName = product.productName,
                maker = product.displayMaker(),
                foodType = product.foodType,
                capacity = product.capacity,
                rawMaterials = product.rawMaterials,
                allergens = product.allergens,
                labelSource = ProcessedFoodLabelSource.of(product.allergens, product.rawMaterials),
                detections = reading.detections.map { ProcessedFoodDetectionResponse(it.code, it.source, it.evidence) },
                unmapped = emptyList(),
                avoidance = avoidance?.let { avoidanceOf(it, reading) },
                source = ProcessedFoodSourceResponse(product.source, "processedFood.source.${product.source.lowercase()}", product.collectedAt),
                image = product.displayImageUrl()?.let { ProcessedFoodImageResponse(it, product.source, "processedFood.image.haccp") },
                reviewSummary = ProcessedFoodReviewSummaryResponse.of(axes),
                verdict = judgement.verdict,
                avoidMatches = judgement.avoidMatches,
                mayContainMatches = judgement.mayContainMatches,
                mayContain = judgement.mayContain.map { ProcessedFoodMayContainResponse(it.code, it.source, it.evidence) },
                unclearIngredients = judgement.unclearIngredients,
                unclearIngredientLabels = UnclearIngredientLabels.labelsOf(judgement.unclearTerms, LanguageCode.KO),
                majorAllergens = judgement.majorAllergens,
                ingredients = product.rawMaterials?.takeUnless(IngredientLabelReader::isRawNotProvided)?.let { ProcessedFoodLocalizedText(it, it) },
                allergenStatement = product.allergens?.takeUnless(IngredientLabelReader::isNotProvided)?.let { ProcessedFoodLocalizedText(it, it) },
            )
        }

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
