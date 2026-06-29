package com.meogo.api.presentation.food

import com.meogo.api.presentation.common.BaseResponse
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
            한국어 메뉴명(menuName)으로 음식 상세를 조회한다. 요청 언어(lang)에 맞춰 음식명·간단 설명(briefDescription)·자세한 설명(detailedDescription)·재료명을 반환하며,
            지원하지 않거나 지정하지 않은 언어는 한국어(ko)로 폴백한다. 설명은 종류별로 독립 폴백한다(간단 번역만 없으면 간단만 ko).

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es.

            각 재료는 포함 비율(inclusionPercent, 0~100)과 mock 위험도(riskStatus: SAFE/CAUTION/DANGER/UNKNOWN)를 함께 제공한다.

            ## 현재 더미에 수록된 메뉴(10종)
            아래 한국어 메뉴명만 조회 가능하다(그 외 메뉴명은 400):
            된장찌개 · 김치찌개 · 비빔밥 · 불고기 · 삼겹살 · 떡볶이 · 김밥 · 잡채 · 순두부찌개 · 물냉면.

            수록되지 않은 메뉴명이나 blank menuName 은 400 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 요청 언어 음식명·간단/자세한 설명·재료 목록 반환"),
            ApiResponse(
                responseCode = "400",
                description = "menuName 누락/blank('menuName은 필수입니다') 또는 미수록 메뉴('해당 음식 정보 없음')",
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
        @Parameter(description = "응답 언어 코드(미지정/미지원 시 ko 폴백)", required = false, example = "en")
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>>
}
