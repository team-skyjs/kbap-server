package com.meogo.app.api.scan

import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 스캔 판정 결과 — scanId 와 itemId 로 매칭되는 항목별 위험도")
data class SubmitMenuScanResponse(
    @field:Schema(description = "저장된 스캔 ID", example = "1")
    val scanId: Long,

    @field:Schema(description = "요청 항목별 판정 결과. 요청의 itemId 로 1:1 매칭된다.")
    val results: List<ItemRiskResponse>,
) {
    @Schema(description = "개별 메뉴 항목의 판정 결과")
    data class ItemRiskResponse(
        @field:Schema(description = "저장된 스캔 항목 ID", example = "10")
        val id: Long,

        @field:Schema(description = "요청에서 받은 itemId — 클라이언트가 메뉴와 결과를 매칭하는 키", example = "0")
        val itemId: Int,

        @field:Schema(
            description = "위험도 판정 결과",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskLevel: String,

        @field:Schema(description = "판정 사유", example = "mock: 안전으로 판정된 항목")
        val reason: String,
    )

    companion object {
        fun from(result: SubmitMenuScanResult): SubmitMenuScanResponse =
            SubmitMenuScanResponse(
                scanId = result.scanId,
                results = result.items.map {
                    ItemRiskResponse(
                        id = it.id,
                        itemId = it.itemId,
                        riskLevel = it.riskLevel,
                        reason = it.reason,
                    )
                },
            )
    }
}
