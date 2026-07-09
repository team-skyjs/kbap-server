package com.meogo.app.api.scan

import com.meogo.application.client.scan.dto.MenuScanResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 스캔 판정 결과 — 요청 idx 로 짝이 맞는 항목별 매칭·위험도")
data class MenuScanResponse(
    @field:Schema(
        description = "정제 서비스 미적용 여부. true 면 LLM 이 없거나 실패해 음식 여부를 판정하지 못한 상태로, " +
            "메뉴가 아닌 텍스트가 results 에 섞여 있을 수 있다(모두 UNMATCHED).",
        example = "false",
    )
    val degraded: Boolean,

    @field:Schema(
        description = "메뉴로 인식된 항목의 판정 결과. 요청의 idx 로 짝을 맞춘다. " +
            "메뉴가 아닌 항목(원산지·가격·UI 문구 등)은 결과에서 제외되므로 요청보다 개수가 적을 수 있다.",
    )
    val results: List<ItemRiskResponse>,
) {
    @Schema(description = "개별 메뉴 항목의 판정 결과")
    data class ItemRiskResponse(
        @field:Schema(description = "요청에서 받은 idx — 클라이언트가 자기 화면의 메뉴와 결과를 연결하는 키", example = "1")
        val idx: Int,

        @field:Schema(
            description = "조회 가능한(완성된) 음식과 매칭됐는지. false 면 조사 대기라 위험도를 알 수 없다(riskLevel=UNKNOWN). " +
                "조사 대기로 새로 등록된 음식도 foodId 는 있으므로, foodId 유무가 아니라 이 값으로 판단한다.",
            example = "true",
        )
        val matched: Boolean,

        @field:Schema(
            description = "음식 식별자(PK). matched=false 여도 조사 대기로 등록된 음식이면 값이 있고, 판정 자체가 불가하면 null",
            example = "7",
            nullable = true,
        )
        val foodId: Long?,

        @field:Schema(
            description = "위험도. matched=true 는 사용자 회피 성분 기준 판정값, matched=false 는 항상 UNKNOWN",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskLevel: String,

        @field:Schema(
            description = "표시명(요청 파라미터로 언어를 지정할 수 없다). 현재는 항상 한국어이며, " +
                "회원 언어 설정이 붙으면 그 언어로 지역화된다(번역이 없으면 한국어 폴백). " +
                "foodId 가 null 이면 서버가 아는 이름이 없어 null",
            example = "김치찌개",
            nullable = true,
        )
        val name: String?,

        @field:Schema(
            description = "언어와 무관한 한국어 메뉴명. foodId 가 null 이면 null",
            example = "김치찌개",
            nullable = true,
        )
        val koreanName: String?,
    )

    companion object {
        fun from(result: MenuScanResult): MenuScanResponse =
            MenuScanResponse(
                degraded = result.degraded,
                results = result.items.map {
                    ItemRiskResponse(
                        idx = it.idx,
                        matched = it.matched,
                        foodId = it.foodId,
                        riskLevel = it.riskLevel,
                        name = it.name,
                        koreanName = it.koreanName,
                    )
                },
            )
    }
}
