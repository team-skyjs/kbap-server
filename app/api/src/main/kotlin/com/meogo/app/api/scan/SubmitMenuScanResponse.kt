package com.meogo.app.api.scan

import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 스캔 판정 결과 — 요청 itemId 로 매칭되는 항목별 정제·매칭·위험도")
data class SubmitMenuScanResponse(
    @field:Schema(description = "요청 항목별 판정 결과. 요청의 itemId 로 1:1 매칭된다.")
    val results: List<ItemRiskResponse>,
) {
    @Schema(description = "개별 메뉴 항목의 판정 결과")
    data class ItemRiskResponse(
        @field:Schema(description = "요청에서 받은 itemId — 클라이언트가 메뉴와 결과를 매칭하는 키", example = "0")
        val itemId: Int,

        @field:Schema(
            description = "위험도 판정 결과. 미완성(조사 대기)·비음식 항목은 UNKNOWN 이다.",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskLevel: String,

        @field:Schema(description = "판정 사유", example = "회피 성분 기준으로 판정했습니다")
        val reason: String,

        @field:Schema(
            description = "정제·매칭 결과. MATCHED=조회 가능한 음식(foodId 존재), PENDING=조사 대기, NOT_FOOD=비음식",
            example = "MATCHED",
            allowableValues = ["MATCHED", "PENDING", "NOT_FOOD"],
        )
        val matchStatus: String,

        @field:Schema(description = "매칭된 음식 id. NOT_FOOD 이거나 판정 불가한 PENDING 이면 null", example = "7", nullable = true)
        val foodId: Long?,
    )

    companion object {
        fun from(result: SubmitMenuScanResult): SubmitMenuScanResponse =
            SubmitMenuScanResponse(
                results = result.items.map {
                    ItemRiskResponse(
                        itemId = it.itemId,
                        riskLevel = it.riskLevel,
                        reason = it.reason,
                        matchStatus = it.matchStatus,
                        foodId = it.foodId,
                    )
                },
            )
    }
}
