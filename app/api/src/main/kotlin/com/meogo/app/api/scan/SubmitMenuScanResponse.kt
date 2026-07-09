package com.meogo.app.api.scan

import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 스캔 판정 결과 — 요청 itemId 로 짝이 맞는 항목별 매칭·위험도")
data class SubmitMenuScanResponse(
    @field:Schema(
        description = "메뉴로 인식된 항목의 판정 결과. 요청의 itemId 로 짝을 맞춘다. " +
            "메뉴가 아닌 항목(원산지·가격·UI 문구 등)은 결과에서 제외되므로 요청보다 개수가 적을 수 있다.",
    )
    val results: List<ItemRiskResponse>,
) {
    @Schema(description = "개별 메뉴 항목의 판정 결과")
    data class ItemRiskResponse(
        @field:Schema(description = "요청에서 받은 itemId — 클라이언트가 자기 화면의 메뉴와 결과를 연결하는 키", example = "1")
        val itemId: Int,

        @field:Schema(
            description = "저장된 음식과의 매칭 결과. MATCHED=조회 가능한 음식으로 매칭됨, UNMATCHED=조사 대기(위험도 판정 불가)",
            example = "MATCHED",
            allowableValues = ["MATCHED", "UNMATCHED"],
        )
        val matchStatus: String,

        @field:Schema(
            description = "음식 식별자(PK). UNMATCHED 여도 조사 대기로 등록된 음식이면 값이 있고, 판정 자체가 불가하면 null",
            example = "7",
            nullable = true,
        )
        val foodId: Long?,

        @field:Schema(
            description = "위험도. MATCHED 는 사용자 회피 성분 기준 판정값, UNMATCHED 는 항상 UNKNOWN",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskLevel: String,
    )

    companion object {
        fun from(result: SubmitMenuScanResult): SubmitMenuScanResponse =
            SubmitMenuScanResponse(
                results = result.items.map {
                    ItemRiskResponse(
                        itemId = it.itemId,
                        matchStatus = it.matchStatus,
                        foodId = it.foodId,
                        riskLevel = it.riskLevel,
                    )
                },
            )
    }
}
