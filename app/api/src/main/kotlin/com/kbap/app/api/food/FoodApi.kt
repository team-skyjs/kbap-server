package com.kbap.app.api.food

import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.Page
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지정/빈/공백은 ko, 지원 목록에 없는 코드는 400.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 최신순 음식 요약(≤20)·nextCursor·hasNext 반환. 각 항목 bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(responseCode = "400", description = "잘못된 커서 형식/음수, 또는 지원 목록에 없는 언어 코드"),
        ],
    )
    fun browse(
        @Parameter(description = "직전 페이지 nextCursor(마지막 항목 foodId). 미지정 시 첫 페이지", required = false, example = "42")
        cursor: String?,
        @Parameter(description = "응답 표시명 언어 코드(미지정/빈/공백 시 ko, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        lang: String?,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>

    @Operation(
        summary = "음식 검색 조회 (무한 스크롤, no-offset)",
        description = """
            검색어가 한국어 음식명 또는 요청 언어(lang) 번역명에 포함되는 음식을 최신 등록순(foodId 내림차순)으로 한 페이지 20개씩 조회한다.
            lang 미지정(ko)이면 한국어명만, 그 외 언어면 한국어명 또는 해당 언어 번역명에 검색어가 포함되면 매칭된다. 대소문자는 구분하지 않는다.
            페이지네이션은 offset 이 아니라 no-offset(cursor/keyset) 방식이다 — 직전 페이지 nextCursor 를 cursor 로 넘기면 그 이후(더 오래된) 20개가 이어진다.
            매칭 음식이 없으면 오류가 아니라 빈 목록을 반환한다. 검색어 없이 전체를 훑는 조회는 음식 목록 조회 API 를 사용한다.

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지정/빈/공백은 ko, 지원 목록에 없는 코드는 400.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 매칭 음식 요약(≤20)·nextCursor·hasNext 반환. 각 항목 bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(responseCode = "400", description = "빈/공백 검색어, 잘못된 커서 형식/음수, 또는 지원 목록에 없는 언어 코드"),
        ],
    )
    fun search(
        @Parameter(description = "검색어(빈/공백 불가). 한국어명 또는 요청 언어 번역명에 부분 일치", required = true, example = "김치")
        keyword: String?,
        @Parameter(description = "직전 페이지 nextCursor(마지막 항목 foodId). 미지정 시 첫 페이지", required = false, example = "42")
        cursor: String?,
        @Parameter(description = "검색·표시명 언어 코드(미지정/빈/공백 시 ko, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        lang: String?,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>

    @Operation(
        summary = "음식 상세 조회",
        description = """
            안정적 식별자 foodId(음식 목록·검색이 각 항목에 내려주는 숫자 id)로 음식 상세를 조회한다. 요청 언어(lang)에 맞춰 음식명·설명(description)·맵기(spiciness)·포함 기피성분명을 반환하며,
            지정하지 않은(또는 빈/공백) 언어는 한국어(ko)로 처리하고, 지원 목록에 없는 코드는 400 으로 거절한다. 설명 번역이 없으면 한국어 원문으로 폴백한다.

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es.

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
                description = "미존재/소프트삭제 음식('해당 음식 정보를 찾을 수 없습니다'), 숫자가 아닌 foodId('잘못된 요청입니다'), 또는 지원 목록에 없는 언어 코드(지원 언어 목록 안내)",
            ),
        ],
    )
    fun detail(
        @Parameter(description = "조회할 음식의 안정적 식별자(음식 목록/검색이 내려준 숫자 id)", required = true, example = "1")
        foodId: Long,
        @Parameter(description = "응답 언어 코드(미지정/빈/공백 시 ko 기본, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        lang: String?,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>>
}
