package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "어드민 - 콘텐츠 초안 검수", description = "공개(READY) 음식의 재수집 결과를 사람이 비교·승인·반려한다")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodContentDraftApi {
    @Operation(
        summary = "검수 대기 콘텐츠 초안 목록",
        description = """
            공개(READY) 음식에 재수집 결과가 오면 공개 내용에 바로 반영하지 않고 초안으로 둔다. 그 초안 중 검수 대기(PENDING)만 오래된 순으로 준다.
            음식당 검수 대기 초안은 하나뿐이다 — 새 결과가 오면 앞 초안은 대체(SUPERSEDED)된다.
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    fun getContentDrafts(
        @Parameter(description = "0부터", example = "0") page: Int?,
        @Parameter(description = "기본·최대 20", example = "20") size: Int?,
    ): ResponseEntity<BaseResponse<AdminFoodContentDraftPageResponse>>

    @Operation(
        summary = "콘텐츠 초안 비교",
        description = "그 음식의 검수 대기 초안을 지금 공개 중인 값(current)과 나란히 준다. 초안이 없으면 404(FOOD-021).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "검수 대기 초안 없음(FOOD-021)"),
        ],
    )
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_CONTENT_DRAFT_NOT_FOUND)
    fun getContentDraft(
        @Parameter(description = "음식 id", required = true, example = "101") foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodContentDraftResponse>>

    @Operation(
        summary = "콘텐츠 초안 승인·반려 (사람 검수 전용)",
        description = """
            **사람(어드민 계정)이 내용을 비교한 뒤 부르는 엔드포인트다.** 자동 검수기(콘텐츠 검수 그래프)는 기존
            `/api/admin/foods/content-reviews` 를 쓰며 이 경로를 부르지 않는다 — 서버는 같은 어드민 토큰 체계라 호출 주체를 구분하지 않는다.
            `passed = true`: 공개 내용(설명·번역·맵기·재료)을 초안으로 바꾸고 재료 표·벡터를 재동기화한다. 음식은 READY 그대로.
            `passed = false`: 초안을 반려하고 공개 내용은 그대로 둔다.
            초안 재료 코드가 지금 카탈로그에 없으면 승인하지 않고 400(FOOD-016)이며 초안은 검수 대기로 남는다 — 반려로 정리한다.
            요청에는 비교 화면에서 받은 `draftId`·`foodVersion` 을 그대로 담는다. 그 사이 새 결과로 초안이 대체됐으면 404(FOOD-021),
            승인 시 공개 내용이 그 사이 바뀌었으면 409(FOOD-006)다 — 보지 않은 초안을 처리하거나 더 새 공개 내용을 덮지 않는다.
            음식이 이미지 재생성으로 READY 를 벗어나 있으면 승인하지 않고 409(FOOD-020)다 — 초안은 대기로 남으니 재생성이 끝난 뒤 승인한다. 반려는 언제든 된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "처리 성공"),
            ApiResponse(responseCode = "400", description = "초안 재료가 카탈로그에 없음(FOOD-016)"),
            ApiResponse(responseCode = "404", description = "검수 대기 초안 없음(FOOD-021)"),
            ApiResponse(responseCode = "409", description = "공개 내용이 비교 뒤 바뀜(FOOD-006) · 음식이 이미지 재생성 중이라 READY 가 아님(FOOD-020)"),
        ],
    )
    @ApiErrors(
        ErrorCode.FOOD_NOT_FOUND,
        ErrorCode.FOOD_CONTENT_DRAFT_NOT_FOUND,
        ErrorCode.FOOD_UNKNOWN_INGREDIENT,
        ErrorCode.FOOD_CONTENT_AND_IMAGE_JOBS_CONFLICT,
        ErrorCode.FOOD_VERSION_CONFLICT,
    )
    fun reviewContentDraft(
        @Parameter(description = "음식 id", required = true, example = "101") foodId: Long,
        adminAccountId: Long,
        request: AdminFoodContentDraftReviewRequest,
    ): ResponseEntity<BaseResponse<AdminFoodContentDraftReviewResponse>>
}
