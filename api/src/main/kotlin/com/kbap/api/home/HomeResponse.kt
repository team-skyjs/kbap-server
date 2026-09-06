package com.kbap.api.home

import com.kbap.api.food.FoodSummaryResponse
import com.kbap.api.review.FoodRating
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "홈 화면 응답 — 기피 성분·인기 음식·최근 스캔 세 섹션")
data class HomeResponse(
    @field:Schema(
        description = "요청자가 로그인한 회원인지 여부. false 면 개인화 섹션이 null 이므로, " +
            "클라이언트는 해당 영역을 가입 유도(블러·모자이크)로 처리한다",
        example = "true",
    )
    val authenticated: Boolean,
    @field:Schema(description = "회원이 설정한 기피 성분. 비회원이거나 설정한 성분이 없으면 빈 배열")
    val avoidedSubstances: List<AvoidedSubstanceResponse>,
    @field:Schema(description = "인기 음식 추천 (최대 5개). 비회원에게도 내려간다")
    val popularFoods: List<FoodSummaryResponse>,
    @field:Schema(description = "최근 스캔한 메뉴 (최대 10개, 최신순·중복 제거). 비회원이거나 이력이 없으면 빈 배열")
    val recentScans: List<HomeRecentScanResponse>,
) {
    @Schema(description = "최근 스캔 카드 — 음식 요약 + 마지막 스캔 시각")
    data class HomeRecentScanResponse(
        val foodId: Long,
        val name: String,
        val koreanName: String?,
        val imageRef: String?,
        val spiciness: Int,
        val overallRiskStatus: String,
        @field:Schema(description = "이 음식을 마지막으로 스캔한 시각, ISO-8601 UTC", example = "2026-08-21T03:00:00Z")
        val scannedAt: java.time.Instant,
        val bookmarked: Boolean,
        val review: FoodSummaryResponse.ReviewInfoResponse,
    ) {
        companion object {
            fun from(view: HomeResult.RecentScanView, bookmarked: Boolean, rating: FoodRating?): HomeRecentScanResponse {
                val summary = FoodSummaryResponse.from(view.food, bookmarked, rating)
                return HomeRecentScanResponse(
                    foodId = summary.foodId,
                    name = summary.name,
                    koreanName = summary.koreanName,
                    imageRef = summary.imageRef,
                    spiciness = summary.spiciness,
                    overallRiskStatus = summary.overallRiskStatus,
                    scannedAt = view.scannedAt,
                    bookmarked = summary.bookmarked,
                    review = summary.review,
                )
            }
        }
    }

    companion object {
        fun from(
            result: HomeResult,
            authenticated: Boolean,
            bookmarkedFoodIds: Set<Long>,
            ratings: Map<Long, FoodRating>,
        ) = HomeResponse(
            authenticated = authenticated,
            avoidedSubstances = result.avoidedSubstances.map(AvoidedSubstanceResponse::from),
            popularFoods = result.popularFoods.map {
                FoodSummaryResponse.from(it, it.foodId in bookmarkedFoodIds, ratings[it.foodId])
            },
            recentScans = result.recentScans.map {
                HomeRecentScanResponse.from(it, it.food.foodId in bookmarkedFoodIds, ratings[it.food.foodId])
            },
        )
    }
}

@Schema(description = "회원이 기피하는 성분")
data class AvoidedSubstanceResponse(
    @field:Schema(description = "회피 성분 코드", example = "EGG")
    val code: String,
    @field:Schema(description = "회원 언어로 지역화된 성분명", example = "Egg")
    val name: String,
) {
    companion object {
        fun from(view: AvoidedSubstanceView) = AvoidedSubstanceResponse(code = view.code, name = view.name)
    }
}
