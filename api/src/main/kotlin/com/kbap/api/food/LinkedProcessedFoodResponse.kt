package com.kbap.api.food

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "메뉴 음식에 연결된 가공식품 대표 상품(S-968) — 표시용(이름·누끼 사진). 위험도·재료 판정은 음식 자체 값을 쓴다")
data class LinkedProcessedFoodResponse(
    @field:Schema(description = "가공식품 id — 가공식품 상세(GET /api/processed-foods/{id})로 이어진다", example = "2866")
    val id: Long,
    @field:Schema(description = "요청 lang 표시 이름(없으면 한국어 원문)", example = "Coca-Cola")
    val name: String,
    @field:Schema(description = "한국어 원문 제품명", example = "코카-콜라")
    val nameKo: String,
    @field:Schema(description = "누끼 이미지 URL(승인된 누끼 우선, 없으면 제품 원본 사진) — 앱은 음식 사진보다 이 값을 먼저 보여 준다", nullable = true)
    val imageUrl: String?,
)
