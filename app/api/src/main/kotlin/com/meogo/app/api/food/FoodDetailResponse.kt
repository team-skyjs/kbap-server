package com.meogo.app.api.food

import com.meogo.application.food.dto.GetFoodDetailResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "음식 상세 — 요청 언어 음식명·간단/자세 설명·대표 이미지·재료 목록")
data class FoodDetailResponse(
    @field:Schema(description = "요청 언어 음식명(미지원/미지정 시 한국어)", example = "Doenjang Stew")
    val name: String,

    @field:Schema(description = "대표 이미지 참조(없을 수 있음)", example = "doenjang.png", nullable = true)
    val imageRef: String?,

    @field:Schema(description = "요청 언어 간단 설명(미지원/미지정/번역 부재 시 한국어)", example = "A hearty Korean soybean paste stew.")
    val briefDescription: String,

    @field:Schema(description = "요청 언어 자세한 설명(미지원/미지정/번역 부재 시 한국어)", example = "Doenjang-jjigae is a traditional Korean stew made with soybean paste.")
    val detailedDescription: String,

    @field:Schema(description = "재료 목록(표시 순서)")
    val ingredients: List<IngredientResponse>,
) {
    @Schema(description = "재료 — 요청 언어 재료명·아이콘·포함 비율·mock 위험도")
    data class IngredientResponse(
        @field:Schema(description = "요청 언어 재료명(미지원/미지정 시 한국어)", example = "Soybean paste")
        val name: String,

        @field:Schema(description = "재료 아이콘 참조(없을 수 있음)", example = "clam.png", nullable = true)
        val iconRef: String?,

        @field:Schema(description = "여러 레시피 기준 포함 비율(0~100)", example = "100")
        val inclusionPercent: Int,

        @field:Schema(
            description = "mock 재료 위험도",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskStatus: String,
    )

    companion object {
        fun from(result: GetFoodDetailResult): FoodDetailResponse =
            FoodDetailResponse(
                name = result.name,
                imageRef = result.imageRef,
                briefDescription = result.briefDescription,
                detailedDescription = result.detailedDescription,
                ingredients = result.ingredients.map {
                    IngredientResponse(
                        name = it.name,
                        iconRef = it.iconRef,
                        inclusionPercent = it.inclusionPercent,
                        riskStatus = it.riskStatus.name,
                    )
                },
            )
    }
}
