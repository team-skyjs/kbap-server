package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "이미지 재생성 접수 결과")
data class AdminFoodImageRegenerateResponse(
    @field:Schema(description = "음식 id", example = "42")
    val foodId: Long,

    @field:Schema(description = "재생성 접수 후 콘텐츠 상태 — 이미지가 붙을 때까지 앱에 노출되지 않는다", example = "PENDING_IMAGE")
    val contentStatus: String,

    @field:Schema(description = "생성된 이미지 배치 항목 id", example = "108")
    val batchItemId: Long,
) {
    companion object {
        fun from(result: AdminFoodImageRegenerateResult): AdminFoodImageRegenerateResponse =
            AdminFoodImageRegenerateResponse(
                foodId = result.foodId,
                contentStatus = result.contentStatus,
                batchItemId = result.batchItemId,
            )
    }
}
