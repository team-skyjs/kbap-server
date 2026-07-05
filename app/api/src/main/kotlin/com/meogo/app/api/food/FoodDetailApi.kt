package com.meogo.app.api.food

import com.meogo.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "음식 상세", description = "메뉴명으로 음식 상세 정보를 조회하는 API")
interface FoodDetailApi {
    @Operation(
        summary = "음식 상세 조회",
        description = """
            한국어 메뉴명(menuName)으로 음식 상세를 조회한다. 요청 언어(lang)에 맞춰 음식명·설명(description)·맵기(spiciness)·포함 기피성분명을 반환하며,
            지정하지 않은(또는 빈/공백) 언어는 한국어(ko)로 처리하고, 지원 목록에 없는 코드는 400 으로 거절한다. 설명 번역이 없으면 한국어 원문으로 폴백한다.

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es.

            맵기(spiciness)는 0~10 정수(0=맵지 않음, 10=매우 매움)로 제공한다.

            각 포함 기피성분의 위험도(riskStatus: SAFE/CAUTION/DANGER/UNKNOWN)는 포함 확률(inclusionPercent, 1~100) 기반 실제값이다(p<10 SAFE · 10~59 CAUTION · ≥60 DANGER).
            응답 최상위 overallRiskStatus 는 사용자 회피 목록 ∩ 음식 성분의 성분별 위험도 최악값이다(현재 회피 목록은 mock 조달).

            ## 현재 더미에 수록된 메뉴(10종)
            아래 한국어 메뉴명만 조회 가능하다(그 외 메뉴명은 400):
            된장찌개 · 김치찌개 · 비빔밥 · 불고기 · 삼겹살 · 떡볶이 · 김밥 · 잡채 · 순두부찌개 · 물냉면.

            수록되지 않은 메뉴명이나 blank menuName 은 400 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 요청 언어 음식명·설명·맵기·포함 기피성분 목록 반환"),
            ApiResponse(
                responseCode = "400",
                description = "menuName 누락/blank('menuName은 필수입니다'), 미수록 메뉴('해당 음식 정보 없음'), 또는 지원 목록에 없는 언어 코드(지원 언어 목록 안내)",
            ),
        ],
    )
    @GetMapping("/detail")
    fun detail(
        @Parameter(
            description = "조회할 한국어 메뉴명(앞뒤 공백은 trim). 현재 더미 수록: 된장찌개·김치찌개·비빔밥·불고기·삼겹살·떡볶이·김밥·잡채·순두부찌개·물냉면",
            required = true,
            example = "된장찌개",
        )
        @RequestParam menuName: String,
        @Parameter(description = "응답 언어 코드(미지정/빈/공백 시 ko 기본, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>>
}
