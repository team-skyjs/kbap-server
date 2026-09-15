package com.kbap.api.food

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "음식 이미지 한 장")
data class FoodImageResponse(
    @field:Schema(description = "해석된 이미지 URL", example = "https://cdn.kbap.site/images/webp/bulgogi.webp")
    val url: String,
)
