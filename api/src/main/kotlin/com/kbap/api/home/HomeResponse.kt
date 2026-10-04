package com.kbap.api.home

import com.kbap.api.food.FoodSummaryResponse
import com.kbap.api.ingredient.AvoidedIngredientView
import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "홈 화면 응답 — 기피 성분·인기 음식·리뷰 많은 음식·최근 스캔 네 섹션")
data class HomeResponse(
    @field:Schema(
        description = "요청자가 로그인한 회원인지 여부. false 면 개인화 섹션이 null 이므로, " +
            "클라이언트는 해당 영역을 가입 유도(블러·모자이크)로 처리한다",
        example = "true",
    )
    val authenticated: Boolean,
    @field:Schema(description = "회원이 설정한 기피 성분. 비회원이거나 설정한 성분이 없으면 빈 배열")
    val avoidedSubstances: List<AvoidedSubstanceResponse>,
    @field:Schema(description = "인기 음식 추천 — 완성(READY) 음식 중 무작위, 호출마다 달라진다. 최대 10개, 중복 없음. 비회원에게도 내려간다")
    val popularFoods: List<FoodSummaryResponse>,
    @field:Schema(
        description = "리뷰 많은 음식 (최대 10개). 활성 리뷰 수 내림차순이고 동률이면 최근 리뷰가 앞선다. " +
            "리뷰가 한 건도 없는 음식은 빠지며 결과가 없으면 빈 배열. 비회원에게도 내려간다",
    )
    val mostReviewedFoods: List<FoodSummaryResponse>,
    @field:Schema(
        description = "최근 스캔한 메뉴 (최대 10개, 최신순·중복 제거). 음식 카드 필드에 scannedAt 이 더해진다. " +
            "비회원이거나 이력이 없으면 빈 배열",
    )
    val recentScans: List<RecentScanResponse>,
) {
    companion object {
        fun from(result: HomeResult, authenticated: Boolean) = HomeResponse(
            authenticated = authenticated,
            avoidedSubstances = result.avoidedSubstances.map(AvoidedSubstanceResponse::from),
            popularFoods = result.popularFoods.map {
                FoodSummaryResponse.from(it, it.foodId in result.bookmarkedFoodIds, result.ratings[it.foodId])
            },
            mostReviewedFoods = result.mostReviewedFoods.map {
                FoodSummaryResponse.from(it, it.foodId in result.bookmarkedFoodIds, result.ratings[it.foodId])
            },
            recentScans = result.recentScans.map {
                RecentScanResponse(
                    food = FoodSummaryResponse.from(it.summary, it.summary.foodId in result.bookmarkedFoodIds, result.ratings[it.summary.foodId]),
                    scannedAt = it.scannedAt,
                )
            },
        )
    }
}

@Schema(description = "최근 스캔한 음식 카드 — 음식 카드(FoodSummary) 필드 전부 + 마지막 스캔 시각")
data class RecentScanResponse(
    @get:JsonUnwrapped
    val food: FoodSummaryResponse,
    @field:Schema(description = "이 회원이 이 음식을 마지막으로 스캔한 시각 (ISO-8601 UTC)", example = "2026-08-21T03:15:00Z")
    val scannedAt: Instant,
)

@Schema(description = "회원이 기피하는 성분")
data class AvoidedSubstanceResponse(
    @field:Schema(description = "회피 성분 코드", example = "EGG")
    val code: String,
    @field:Schema(description = "회원 언어로 지역화된 성분명", example = "Egg")
    val name: String,
) {
    companion object {
        fun from(view: AvoidedIngredientView) = AvoidedSubstanceResponse(code = view.code, name = view.name)
    }
}
