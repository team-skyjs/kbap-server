package com.kbap.api.food

import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "화제 영상이 연결된 K-푸드 목록")
data class IdolPicksResponse(
    @field:Schema(description = "음식당 1건. 대표 영상 화제성 내림차순 → 게시월 내림차순 → foodId 오름차순. 없으면 빈 배열")
    val items: List<IdolPickResponse>,
)

@Schema(description = "화제 영상 K-푸드 항목 — 음식 카드(FoodSummary) 필드 전부 + 대표 영상")
data class IdolPickResponse(
    @get:JsonUnwrapped
    val food: FoodSummaryResponse,
    @field:Schema(description = "대표 영상(이 음식의 연결 영상 중 position 최소). null 이 아니다")
    val video: IdolPickVideoResponse,
)

@Schema(description = "대표 영상 — 외부 영상 URL 과 플랫폼")
data class IdolPickVideoResponse(
    @field:Schema(description = "외부 영상 URL", example = "https://www.youtube.com/watch?v=4gTHQqAKNA8")
    val url: String,
    @field:Schema(description = "영상 플랫폼", example = "YOUTUBE", allowableValues = ["YOUTUBE", "TIKTOK"])
    val platform: String,
)
