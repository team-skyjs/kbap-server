package com.kbap.api.food

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.Page
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

            응답은 세 층이다 — 음식 고유 정보는 food 객체(name·koreanName·imageRef·description·spiciness·ingredients)로 묶여 조회자와 무관하게 동일하고,
            조회자 맥락(overallRiskStatus·avoidedIngredients·bookmarked)과 리뷰(reviewSummary·recentReviews)가 최상위에 온다.

            food.ingredients 는 음식 재료 전체({code, name(요청 언어), inclusionPercent}, 확률 내림차순)이고,
            avoidedIngredients 는 조회 회원 회피성분과의 교집합({code, riskStatus}, 확률 내림차순)이다. riskStatus 는 포함 확률 기반
            실제값(p<10 SAFE · 10~59 CAUTION · ≥60 DANGER)이며, UI 는 code 로 두 목록을 조인한다. 비회원 조회의 avoidedIngredients 는 null 이다(회원의 겹침 없음은 빈 배열).

            리뷰는 두 필드로 내려간다 — reviewSummary.overall(전체 사용자)·reviewSummary.sameCountry(같은 국적, 작성 시점 스냅샷 기준)가
            같은 형태(averageRating: 평균 별점 소수 첫째 자리 반올림 · reviewCount: 리뷰 수)로 제공되고,
            recentReviews 는 최신순 최대 5개 리뷰를 리뷰 목록 API(GET /api/reviews)와 동일한 항목 형태로 동봉한다
            (이 음식에 대한 리뷰이므로 항목의 food 필드는 생략, createdAt 은 epoch millis, author 에 profileImageUrl 포함).
            overall 은 회원·비회원 모두 실제 집계값이며 수치는 null 없이 항상 숫자다(해당 값이 없으면 0.0·0).
            sameCountry 는 비회원(또는 탈퇴 회원 토큰) 조회면 null, 회원 조회면 항상 객체다(국적 미보유·해당 국적 리뷰 없음이면 0.0·0).
            recentReviews 의 likedByMe 는 비회원 조회면 항상 false 이고, 차단·신고 리뷰 제외는 회원 조회에만 적용된다.

            응답 최상위 overallRiskStatus 는 사용자 회피 목록 ∩ 음식 성분의 성분별 위험도 최악값이며, 비회원 조회는 판별하지 않고 null 이다.
            클라이언트 판별 규칙: overallRiskStatus == null → 비회원 조회 응답(로그인 유도 등 비회원 UI 분기 기준).

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
