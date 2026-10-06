package com.kbap.api.food

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.Page
import com.kbap.api.core.SearchPage
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
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

            risk(CSV, 옵션)로 위험도 필터를 건다 — 조회자 기준 overallRiskStatus 가 지정 집합(SAFE·CAUTION·DANGER·UNKNOWN, OR)에 드는 음식만 서버가 걸러 내려준다. 미정의 값은 400(COMMON-002). 비회원도 사용 가능(회피성분이 없으면 도달 불가한 DANGER·CAUTION 만 요청 시 즉시 빈 페이지).
            **risk 필터 시 items 가 PAGE_SIZE 미만이어도 hasNext=true 일 수 있다(요청당 스캔 상한 — 얇은 페이지 허용). 종료 판단은 items 개수가 아니라 hasNext/nextCursor 로만 한다** — hasNext=true 면 nextCursor 로 계속 당긴다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 최신순 음식 요약(≤20)·nextCursor·hasNext 반환. 각 항목 bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(responseCode = "400", description = "잘못된 커서 형식/음수, lang 누락·빈/공백, 미정의 risk 값(COMMON-002)"),
        ],
    )
    @ApiErrors(ErrorCode.INVALID_CURSOR)
    fun browse(
        @ParameterObject request: FoodBrowseRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>

    @Operation(
        summary = "음식 검색 조회 (무한 스크롤, no-offset)",
        description = """
            검색어가 음식 이름에 포함되는 음식을 **관련도 순**으로 조회한다. lang 은 표시 언어만 정하고 검색 범위에는 영향이 없다.

            ## 어느 언어로 쳐도 찾는다 (KB-721)
            한국어명·표시명·모든 언어의 번역명(10개 로케일)을 전부 본다 — ja 앱에서 "bibimbap"·"비빔밥"·"ビビンバ" 모두 비빔밥을 찾는다.
            - 검색어에 한글이 있으면 **한글만 남겨** 비교한다 — "김치 찌개"·"김치찌개"·"김치-찌개!"는 같은 결과다. 띄어쓰기·기호는 무시된다.
            - 한글이 없으면 소문자·공백 축약으로 비교한다 — "KIMCHI   stew"는 "Kimchi Stew"와 같다. 대소문자는 구분하지 않는다.

            ## 정렬 — 관련도 (scope=all)
            1. 이름과 **정확히 일치** → 2. 이름이 검색어로 **시작** → 3. 이름에 **포함**. 여러 이름 중 가장 좋은 등급을 쓴다.
            같은 등급 안에서는 **많이 스캔된 음식 순**(scan_history 건수), 그다음 foodId 내림차순. "김밥"을 치면 `김밥` → `참치김밥` → `돈까스떡볶이원조김밥` 순이다.

            매칭 음식이 없으면 오류가 아니라 빈 목록을 반환한다. 검색어 없이 전체를 훑는 조회는 음식 목록 조회 API 를 사용한다.

            keyword 는 scope 무관 **필수**(누락·빈/공백 400)다 — 검색어 입력 전 초기 화면은 이 API 가 아니라 스캔 내역 조회로 구성한다.

            scope 파라미터로 검색 범위를 고른다(미지정 시 all):
            - **scope=all**(기본) — 위 관련도 순으로 한 페이지 20개씩, no-offset(cursor/keyset) 페이징.
              **nextCursor 는 불투명 문자열**(등급·스캔 수·foodId 의 복합 키)이다 — 해석하지 말고 그대로 cursor 로 돌려준다. 형식은 바뀔 수 있다.
              예전 형식의 커서(foodId 숫자)가 오면 400 이 아니라 첫 페이지로 해석한다. 비회원 사용 가능.
            - **scope=scanned** — 본인 스캔 이력에 매칭된 음식만, 중복 없이 마지막 스캔 시점 내림차순(리뷰 태그 검색 용도 — 재스캔하면 맨 앞으로).
              **회원 전용**(인증 없으면 401). **페이징 없이 매칭 전체를 한 번에 반환**한다(hasNext 항상 false·nextCursor 항상 null, cursor 파라미터는 무시).
              삭제·비공개 음식과 음식 미매칭 스캔 항목은 제외되고 매칭 없으면 빈 목록이다.

            scope 값이 all·scanned 외면 400(COMMON-002)이다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. lang 은 **필수**이며 누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 매칭 음식 요약(all ≤20·scanned 전체)·nextCursor·hasNext 반환. 각 항목 bookmarked 는 조회 회원의 북마크 여부(비회원은 항상 false)"),
            ApiResponse(responseCode = "400", description = "검색어 누락·빈/공백, 지원하지 않는 scope, 깨진 커서 형식(FOOD-002 — 예전 숫자 커서는 400 이 아니라 첫 페이지), 또는 lang 누락·빈/공백"),
            ApiResponse(responseCode = "401", description = "scope=scanned 를 인증 없이 호출"),
        ],
    )
    @ApiErrors(
        ErrorCode.BLANK_SEARCH_KEYWORD,
        ErrorCode.INVALID_CURSOR,
    )
    fun search(
        @ParameterObject request: FoodSearchRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<SearchPage<FoodSummaryResponse>>>

    @Operation(
        summary = "스캔 음식 목록 조회 (리뷰 태그 초기 화면, 회원 전용)",
        description = """
            본인 스캔 이력에 매칭된 음식을 중복 없이 마지막 스캔 시점 내림차순으로 한 페이지 20개씩 조회한다.
            리뷰 태그 화면의 검색어 입력 전 초기 목록 용도다 — 같은 음식을 여러 번 스캔했어도 한 번만 나오고, 재스캔하면 맨 앞으로 올라온다.
            검색어로 거르는 조회는 음식 검색 API(scope=scanned)를 사용한다.

            삭제·비공개 음식과 음식 마스터에 매칭되지 않은 스캔 항목은 제외되며, 스캔 이력이 없으면 빈 목록이다.

            페이지네이션은 no-offset(cursor/keyset) 방식이다 — 직전 페이지 nextCursor(마지막 항목 foodId)를 cursor 로 넘기면 이어진다.
            형식이 잘못됐거나 본인 스캔 이력에 없는 음식의 커서는 400(FOOD-002)이다.

            회원 전용 API 다 — 인증 없는 호출은 401 이다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. lang 은 **필수**이며 누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 스캔 음식 요약(≤20, 최신 스캔순)·nextCursor·hasNext 반환. 항목 형태는 음식 목록과 동일"),
            ApiResponse(responseCode = "400", description = "잘못된 커서(형식·본인 스캔 이력에 없음) 또는 lang 누락·빈/공백"),
            ApiResponse(responseCode = "401", description = "인증 없음 — 회원 전용"),
        ],
    )
    @ApiErrors(ErrorCode.INVALID_CURSOR)
    fun scanned(
        @ParameterObject request: FoodScannedRequest,
        memberId: Long,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>

    @Operation(
        summary = "음식 상세 조회",
        description = """
            안정적 식별자 foodId(음식 목록·검색이 각 항목에 내려주는 숫자 id)로 음식 상세를 조회한다. 요청 언어(lang)에 맞춰 음식명·설명(description)·맵기(spiciness)·포함 기피성분명을 반환하며,
            lang 은 필수이며 누락·빈/공백은 400 으로 거절하고, 지원 목록에 없는 코드는 en 으로 응답한다. 설명 번역이 없으면 한국어 원문으로 폴백한다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es.

            맵기(spiciness)는 0~10 정수(0=맵지 않음, 10=매우 매움)로 제공한다.

            ingredients 는 음식 재료 전체({code, name(요청 언어), inclusionPercent}, 확률 내림차순)로 회원·비회원 공통이고,
            avoidedIngredients 는 조회 회원 회피성분과의 교집합({code, riskStatus}, 확률 내림차순)이다. riskStatus 는 포함 확률 기반
            실제값(p<10 SAFE · 10~59 CAUTION · ≥60 DANGER)이며, UI 는 code 로 두 목록을 조인한다. 비회원 조회의 avoidedIngredients 는 null 이다(회원의 겹침 없음은 빈 배열).

            리뷰는 두 필드로 내려간다 — reviewSummary.overall(전체 사용자)·reviewSummary.sameCountry(같은 국적, 작성 시점 스냅샷 기준)가
            같은 형태(averageRating: 평균 별점 소수 첫째 자리 반올림 · reviewCount: 리뷰 수)로 제공되고,
            images 는 이미지 갤러리다 — 대표(imageRef 와 같은 URL)가 항상 첫 번째이고 이후는 정렬 순서다. 갤러리 행이 아직 없는 음식은 빈 배열이며, 대표 이미지 정본은 여전히 imageRef 다.
            recentReviews 는 최신순 최대 5개 리뷰를 리뷰 목록 API(GET /api/reviews)와 동일한 항목 형태로 동봉한다
            (이 음식에 대한 리뷰이므로 항목의 food 필드는 생략, createdAt 은 epoch millis, author 에 profileImageUrl 포함).
            overall 은 회원·비회원 모두 실제 집계값이며 수치는 null 없이 항상 숫자다(해당 값이 없으면 0.0·0).
            sameCountry 는 비회원(또는 탈퇴 회원 토큰) 조회면 null, 회원 조회면 항상 객체다(국적 미보유·해당 국적 리뷰 없음이면 0.0·0).
            recentReviews 의 likedByMe 는 비회원 조회면 항상 false 다. 차단한 회원의 리뷰 제외는 회원 조회에만 적용된다.

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
                description = "미존재/소프트삭제 음식(FOOD-001), 존재하지만 공개(READY) 전이거나 이미지 재생성으로 숨겨진 음식(FOOD-018 — 다시 공개될 수 있다), 숫자가 아닌 foodId 또는 lang 누락·빈/공백('잘못된 요청입니다')",
            ),
        ],
    )
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_NOT_PUBLIC)
    fun detail(
        @Parameter(description = "조회할 음식의 안정적 식별자(음식 목록/검색이 내려준 숫자 id)", required = true, example = "1")
        foodId: Long,
        @ParameterObject request: FoodDetailRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>>
}
