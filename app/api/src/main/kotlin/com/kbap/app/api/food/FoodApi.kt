package com.kbap.app.api.food

import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.Page
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "음식", description = "음식 목록·검색·상세 조회 API")
@SecurityRequirement(name = "bearerAuth")
interface FoodApi {
    @Operation(
        summary = "음식 목록 조회 (무한 스크롤, no-offset)",
        description = """
            검색어 없이 전체 음식을 최신 등록순(foodId 내림차순)으로 한 페이지 20개씩 조회한다.
            페이지네이션은 offset 이 아니라 no-offset(cursor/keyset) 방식이다 — 직전 페이지 nextCursor 를 cursor 로 넘기면 그 이후(더 오래된) 20개가 이어진다.
            cursor 미지정 시 첫 페이지(최신 20개)로 해석한다. 응답은 다음 커서(nextCursor)와 다음 페이지 존재 여부(hasNext)를 포함한다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. lang 은 **필수**이며 누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 최신순 음식 요약(≤20)·nextCursor·hasNext 반환. 각 항목 bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(responseCode = "400", description = "잘못된 커서 형식/음수, 또는 lang 누락·빈/공백"),
        ],
    )
    fun browse(
        @ParameterObject request: FoodBrowseRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>

    @Operation(
        summary = "음식 검색 조회 (무한 스크롤, no-offset)",
        description = """
            검색어가 한국어 음식명 또는 요청 언어(lang) 번역명에 포함되는 음식을 최신 등록순(foodId 내림차순)으로 한 페이지 20개씩 조회한다.
            lang=ko 이면 한국어명만, 그 외 언어면 한국어명 또는 해당 언어 번역명에 검색어가 포함되면 매칭된다. 대소문자는 구분하지 않는다.
            페이지네이션은 offset 이 아니라 no-offset(cursor/keyset) 방식이다 — 직전 페이지 nextCursor 를 cursor 로 넘기면 그 이후(더 오래된) 20개가 이어진다.
            매칭 음식이 없으면 오류가 아니라 빈 목록을 반환한다. 검색어 없이 전체를 훑는 조회는 음식 목록 조회 API 를 사용한다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. lang 은 **필수**이며 누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 매칭 음식 요약(≤20)·nextCursor·hasNext 반환. 각 항목 bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(responseCode = "400", description = "빈/공백 검색어, 잘못된 커서 형식/음수, 또는 lang 누락·빈/공백"),
        ],
    )
    fun search(
        @ParameterObject request: FoodSearchRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>

    @Operation(
        summary = "음식 상세 조회",
        description = """
            안정적 식별자 foodId(음식 목록·검색이 각 항목에 내려주는 숫자 id)로 음식 상세를 조회한다. 요청 언어(lang)에 맞춰 음식명·설명(description)·맵기(spiciness)·포함 기피성분명을 반환하며,
            lang 은 필수이며 누락·빈/공백은 400 으로 거절하고, 지원 목록에 없는 코드는 en 으로 응답한다. 설명 번역이 없으면 한국어 원문으로 폴백한다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es.

            맵기(spiciness)는 0~10 정수(0=맵지 않음, 10=매우 매움)로 제공한다.

            각 포함 기피성분의 위험도(riskStatus: SAFE/CAUTION/DANGER/UNKNOWN)는 포함 확률(inclusionPercent, 1~100) 기반 실제값이다(p<10 SAFE · 10~59 CAUTION · ≥60 DANGER).
            응답 최상위 overallRiskStatus 는 사용자 회피 목록 ∩ 음식 성분의 성분별 위험도 최악값이다(현재 회피 목록은 mock 조달).

            존재하지 않는 foodId, 소프트삭제된 음식, 숫자가 아닌 foodId 는 모두 400 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 요청 언어 음식명·설명·맵기·포함 기피성분 목록 반환. bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(
                responseCode = "400",
                description = "미존재/소프트삭제 음식('해당 음식 정보를 찾을 수 없습니다'), 숫자가 아닌 foodId 또는 lang 누락·빈/공백('잘못된 요청입니다')",
            ),
        ],
    )
    fun detail(
        @Parameter(description = "조회할 음식의 안정적 식별자(음식 목록/검색이 내려준 숫자 id)", required = true, example = "1")
        foodId: Long,
        @ParameterObject request: FoodDetailRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>>
}
