package com.meogo.app.api.food

import com.meogo.application.client.food.dto.GetFoodDetailResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "음식 상세 — 요청 언어 음식명·설명·맵기·대표 이미지·포함 기피성분 목록")
data class FoodDetailResponse(
    @field:Schema(description = "요청 언어 음식명(미지원/미지정 시 한국어)", example = "Doenjang Stew")
    val name: String,

    @field:Schema(description = "대표 이미지 참조(없을 수 있음)", example = "doenjang.png", nullable = true)
    val imageRef: String?,

    @field:Schema(description = "요청 언어 설명(미지원/미지정/번역 부재 시 한국어)", example = "A hearty Korean soybean paste stew.")
    val description: String,

    @field:Schema(description = "맵기 정도(0~10, 0=맵지 않음 · 10=매우 매움)", example = "3")
    val spiciness: Int,

    @field:Schema(description = "포함 기피성분 목록(포함 확률 내림차순)")
    val ingredients: List<IngredientResponse>,
) {
    @Schema(description = "포함 기피성분 — 요청 언어 성분명·아이콘·포함 확률·mock 위험도")
    data class IngredientResponse(
        @field:Schema(description = "요청 언어 성분명(미지원/미지정/번역 부재 시 한국어)", example = "Soybean")
        val name: String,

        @field:Schema(description = "성분 아이콘 참조(현재 미제공)", example = "clam.png", nullable = true)
        val iconRef: String?,

        @field:Schema(description = "포함 확률(1~100)", example = "100")
        val inclusionPercent: Int,

        @field:Schema(
            description = "mock 기피성분 위험도",
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
                description = result.description,
                spiciness = result.spiciness,
                ingredients = result.avoidanceSubstances.map {
                    IngredientResponse(
                        name = it.name,
                        iconRef = it.iconRef,
                        inclusionPercent = it.inclusionProbability,
                        riskStatus = it.riskStatus.name,
                    )
                },
            )
    }
}
