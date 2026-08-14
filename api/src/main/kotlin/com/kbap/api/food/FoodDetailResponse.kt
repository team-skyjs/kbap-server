package com.kbap.api.food

import com.kbap.api.review.RatingSummary
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "음식 상세 — 요청 언어 음식명·설명·맵기·대표 이미지·포함 기피성분 목록")
data class FoodDetailResponse(
    @field:Schema(description = "요청 언어 음식명(미지원/미지정 시 한국어)", example = "Doenjang Stew")
    val name: String,

    @field:Schema(
        description = "언어 무관 한국어 음식명. 지역화 음식명이 곧 한국어면(lang=ko·번역 부재 폴백) null.",
        example = "된장찌개",
        nullable = true,
    )
    val koreanName: String?,

    @field:Schema(description = "대표 이미지 참조(없을 수 있음)", example = "doenjang.png", nullable = true)
    val imageRef: String?,

    @field:Schema(description = "요청 언어 설명(미지원/미지정/번역 부재 시 한국어)", example = "A hearty Korean soybean paste stew.")
    val description: String,

    @field:Schema(description = "맵기 정도(0~10, 0=맵지 않음 · 10=매우 매움)", example = "3")
    val spiciness: Int,

    @field:Schema(
        description = "음식 종합 위험도(사용자 회피 ∩ 음식 성분의 성분별 위험도 최악값). 비회원 조회는 판별하지 않고 null — 비회원 응답 판별 기준.",
        example = "DANGER",
        allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        nullable = true,
    )
    val overallRiskStatus: String?,

    @field:Schema(description = "포함 기피성분 목록(포함 확률 내림차순)")
    val ingredients: List<IngredientResponse>,

    @field:Schema(description = "조회 회원의 북마크 여부. 비회원 조회는 항상 false.", example = "true")
    val bookmarked: Boolean,

    @field:Schema(description = "리뷰 요약 — 전체 평균 별점·리뷰 수·같은 국적 평균 별점")
    val review: ReviewSummaryResponse,
) {
    @Schema(description = "음식 상세의 리뷰 요약 묶음 — 전체(overall)·같은 국적(sameCountry) 평점을 같은 형태로 제공")
    data class ReviewSummaryResponse(
        @field:Schema(description = "전체 사용자 리뷰 요약")
        val overall: ReviewRatingResponse,

        @field:Schema(
            description = "조회 회원과 같은 국적(작성 시점 스냅샷 기준) 리뷰 요약. 국적 미보유·해당 국적 리뷰 없음이면 기본값(0.0·0). 비회원 조회는 null.",
            nullable = true,
        )
        val sameCountry: ReviewRatingResponse?,

        @field:Schema(description = "비회원 가림 여부 — true 면 수치는 기본값(0.0·0)이며 '리뷰 없음'과 구분용. 활성 회원 조회는 false.", example = "false")
        val blur: Boolean,
    ) {
        @Schema(description = "리뷰 평점 요약 — 평균 별점·리뷰 수")
        data class ReviewRatingResponse(
            @field:Schema(description = "평균 별점(소수 첫째 자리 반올림). 리뷰가 없으면 0.0 — null 없음.", example = "3.7")
            val averageRating: Double,

            @field:Schema(description = "리뷰 수", example = "3")
            val reviewCount: Long,
        )

        companion object {
            fun from(rating: RatingSummary, sameCountryVisible: Boolean): ReviewSummaryResponse =
                ReviewSummaryResponse(
                    overall = ReviewRatingResponse(
                        averageRating = rating.averageRating ?: 0.0,
                        reviewCount = rating.reviewCount,
                    ),
                    sameCountry = if (sameCountryVisible) {
                        ReviewRatingResponse(
                            averageRating = rating.sameCountryAverageRating ?: 0.0,
                            reviewCount = rating.sameCountryReviewCount,
                        )
                    } else {
                        null
                    },
                    blur = false,
                )
        }
    }

    @Schema(description = "포함 기피성분 — 요청 언어 성분명·아이콘·포함 확률·포함 확률 기반 위험도")
    data class IngredientResponse(
        @field:Schema(description = "요청 언어 성분명(미지원/미지정/번역 부재 시 한국어)", example = "Soybean")
        val name: String,

        @field:Schema(description = "성분 아이콘 참조(현재 미제공)", example = "clam.png", nullable = true)
        val iconRef: String?,

        @field:Schema(description = "포함 확률(1~100)", example = "100")
        val inclusionPercent: Int,

        @field:Schema(
            description = "포함 확률 기반 실제 기피성분 위험도",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskStatus: String,
    )

    companion object {
        fun from(result: GetFoodDetailResult, bookmarked: Boolean, review: ReviewSummaryResponse): FoodDetailResponse =
            FoodDetailResponse(
                name = result.name,
                koreanName = result.koreanName,
                imageRef = result.imageRef,
                description = result.description,
                spiciness = result.spiciness,
                overallRiskStatus = result.overallRiskStatus?.name,
                ingredients = result.ingredients.map {
                    IngredientResponse(
                        name = it.name,
                        iconRef = it.iconRef,
                        inclusionPercent = it.inclusionProbability,
                        riskStatus = it.riskStatus.name,
                    )
                },
                bookmarked = bookmarked,
                review = review,
            )
    }
}
