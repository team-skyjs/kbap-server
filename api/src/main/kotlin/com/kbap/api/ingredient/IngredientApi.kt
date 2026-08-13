package com.kbap.api.ingredient

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "재료", description = "재료 카탈로그 조회 API — 경로는 /api/ingredients(URI 무버전), 버전은 X-API-Version 헤더(기본 1.0, 미지원 버전 400)로 전달한다")
interface IngredientApi {
    @Operation(
        summary = "재료 카탈로그 목록 조회",
        description = """
            온보딩 기피 재료 선택 화면용 재료 카탈로그 전체 목록을 내려준다 — 재료 코드·표시명·이미지 URL.

            ## 버전
            `X-API-Version` 헤더 미전송·`1.0` 은 현재 계약이다(신규 API — 1.0 이 최초 버전).

            ## 인증 (불필요)
            **인증 없이 호출하는 공개 API** 다. `Authorization` 헤더가 있어도 검사하지 않는다(무효 토큰이어도 200).

            ## 언어
            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. `lang` 은 **필수**이며
            누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다. 번역이 없는 재료는 한국어로 폴백한다.

            ## 목록
            항상 카탈로그 전체를 id 오름차순으로 내려준다(페이지네이션 없음). 이미지가 아직 매칭되지 않은
            재료는 `imageUrl` 이 null 이다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 재료 전체 목록"),
            ApiResponse(responseCode = "400", description = "lang 누락·빈/공백"),
        ],
    )
    fun getIngredients(
        @ParameterObject request: IngredientListRequest,
    ): ResponseEntity<BaseResponse<IngredientListResponse>>
}
