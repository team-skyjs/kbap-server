package com.kbap.api.scan

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 스캔 판정 결과 — 요청 idx 로 짝이 맞는 항목별 매칭·위험도")
data class ScanResponse(
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
        @field:Schema(
            description = "이 추출 메뉴에 매칭된 클라이언트 OCR 항목의 idx. 클라이언트는 이 값으로 해당 메뉴 위에 박스를 그린다. " +
                "사진에서 추출됐지만 대응하는 OCR 항목이 없으면 null(그릴 박스 없음).",
            example = "0",
            nullable = true,
        )
        val idx: Int?,

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
            description = "표시용 메뉴명. matched=true 면 음식의 요청 lang 번역명(해당 언어 번역이 없으면 한국어명), " +
                "matched=false 면 아직 번역본이 없는 신규 음식이므로 비전이 정제한 표준 한국어명.",
            example = "Kimchi Stew",
            nullable = true,
        )
        val name: String?,

        @field:Schema(
            description = "언어와 무관한 한국어 메뉴명. 매칭되면 저장된 음식의 표준 한국어명, 미매칭이면 비전이 정제한 표준 한국어명",
            example = "김치찌개",
            nullable = true,
        )
        val koreanName: String?,

        @field:Schema(
            description = "메뉴판에 표기된 가격(원 단위 정수). 축약 표기(\"1.6\", \"9.0\")는 천원 단위로 복원한다. " +
                "가격이 표기되지 않은 메뉴는 null. 가격은 응답으로만 제공되며 음식 마스터에는 저장하지 않는다.",
            example = "9000",
            nullable = true,
        )
        val price: Int?,
    )

    companion object {
        fun from(result: ScanResult): ScanResponse =
            ScanResponse(
                degraded = result.degraded,
                results = result.items.map {
                    ItemRiskResponse(
                        idx = it.idx,
                        matched = it.matched,
                        foodId = it.foodId,
                        riskLevel = it.riskLevel,
                        name = it.name,
                        koreanName = it.koreanName,
                        price = it.price,
                    )
                },
            )
    }
}
