package com.kbap.api.scan

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 스캔 v2 판정 결과 — 사진에서 서버가 추출한 메뉴별 매칭·위험도")
data class ScanV2Response(
    @field:Schema(
        description = "정제 서비스 미적용 여부. true 면 LLM 이 없거나 실패해 음식 여부를 판정하지 못한 상태다.",
        example = "false",
    )
    val degraded: Boolean,

    @field:Schema(
        description = "사진에서 추출된 메뉴의 판정 결과. 순서는 추출 순서이며 클라이언트 항목과의 매칭 키(idx)는 없다.",
    )
    val results: List<ItemRiskResponse>,
) {
    @Schema(description = "개별 메뉴 항목의 판정 결과")
    data class ItemRiskResponse(
        @field:Schema(
            description = "조회 가능한(완성된) 음식과 매칭됐는지. false 면 조사 대기라 위험도를 알 수 없다(riskLevel=UNKNOWN).",
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
            description = "표시용 메뉴명. matched=true 면 음식의 요청 lang 번역명(번역 부재 시 한국어명), " +
                "matched=false 면 비전이 정제한 표준 한국어명.",
            example = "Kimchi Stew",
            nullable = true,
        )
        val name: String?,

        @field:Schema(
            description = "언어와 무관한 한국어 메뉴명(원본 표기 보존).",
            example = "김치 찌개",
            nullable = true,
        )
        val koreanName: String?,

        @field:Schema(
            description = "메뉴판에 표기된 가격(원 단위 정수). 표기가 없으면 null.",
            example = "9000",
            nullable = true,
        )
        val price: Int?,

        @field:Schema(
            description = "미등록(matched=false) 메뉴의 유사 음식 대체 결과. 값이 있으면 클라이언트는 '정확 매칭 아님' 주의 표시와 " +
                "함께 이 음식을 보여준다. foodId 는 항상 조회 가능한 등록 음식이라 상세 조회 API 와 연동된다. " +
                "matched=true 이거나 충분히 비슷한 음식이 없으면 null.",
            nullable = true,
        )
        val similarFood: SimilarFoodResponse?,
    )

    @Schema(description = "유사 음식 대체 결과 — 필드 의미는 음식 상세 응답과 동일")
    data class SimilarFoodResponse(
        @field:Schema(description = "유사 음식 식별자(PK) — 음식 상세 조회 가능", example = "12")
        val foodId: Long,

        @field:Schema(description = "요청 언어 음식명(번역 부재 시 한국어)", example = "Kimchi Stew")
        val name: String,

        @field:Schema(description = "언어 무관 한국어 음식명. 지역화 음식명이 곧 한국어면 null.", example = "김치찌개", nullable = true)
        val koreanName: String?,

        @field:Schema(description = "요청 언어 설명(번역 부재 시 한국어)", example = "Spicy fermented cabbage stew.")
        val description: String,

        @field:Schema(description = "대표 이미지 참조(없을 수 있음)", example = "https://cdn.example.com/foods/12.jpg", nullable = true)
        val imageRef: String?,
    )

    companion object {
        fun from(result: ScanResult): ScanV2Response =
            ScanV2Response(
                degraded = result.degraded,
                results = result.items.map {
                    ItemRiskResponse(
                        matched = it.matched,
                        foodId = it.foodId,
                        riskLevel = it.riskLevel,
                        name = it.name,
                        koreanName = it.koreanName,
                        price = it.price,
                        similarFood = it.similarFood?.let { similar ->
                            SimilarFoodResponse(
                                foodId = similar.foodId,
                                name = similar.name,
                                koreanName = similar.koreanName,
                                description = similar.description,
                                imageRef = similar.imageRef,
                            )
                        },
                    )
                },
            )
    }
}
