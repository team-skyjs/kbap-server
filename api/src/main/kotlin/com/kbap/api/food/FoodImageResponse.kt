package com.kbap.api.food

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "음식 이미지 한 장")
data class FoodImageResponse(
    @field:Schema(description = "해석된 이미지 URL", example = "https://cdn.kbap.site/images/webp/bulgogi.webp")
    val url: String,

    @field:Schema(description = "대표 이미지 여부. 목록에서 대표가 항상 첫 번째다", example = "true")
    val isPrimary: Boolean,
)
