package com.kbap.api.review

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

@Tag(
    name = "리뷰",
    description = "음식 리뷰 작성·수정·삭제 API — 경로는 /api/reviews(URI 무버전), 버전은 X-API-Version 헤더(기본 1.0, 미지원 버전 400)로 전달한다",
)
@SecurityRequirement(name = "bearerAuth")
interface ReviewApi {
    @Operation(
        summary = "리뷰 작성",
        description = """
            음식에 리뷰를 작성한다. 별점(1~5)은 필수, 본문(최대 1000자)·사진(최대 3장)·식당(place)은 옵션이다.
            사진은 REVIEW 용도 presigned 업로드를 완료한 본인 소유 경로만 허용한다.
            place 는 식당 검색(GET /api/v1/places)에서 고른 항목을 그대로 넣는다 — 고르지 않았으면 생략한다.
            같은 회원이 같은 음식에 여러 건 작성할 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작성 성공"),
            ApiResponse(responseCode = "400", description = "검증 실패(별점 범위·본문 길이·사진 수), 미존재 음식(FOOD-001), 미소유 이미지(REVIEW-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun create(memberId: Long, request: ReviewCreateRequest): ResponseEntity<BaseResponse<ReviewResponse>>

    @Operation(
        summary = "리뷰 수정",
        description = "본인 리뷰의 별점·본문·사진·식당을 수정한다. content·imagePaths·place 는 보낸 값으로 전량 교체된다(생략 시 제거). 작성 시점 국적 스냅샷은 바뀌지 않는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공"),
            ApiResponse(responseCode = "400", description = "미존재/삭제된 리뷰(REVIEW-001), 검증 실패, 미소유 이미지(REVIEW-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "403", description = "타인 리뷰(REVIEW-002)"),
        ],
    )
    fun update(
        memberId: Long,
        @Parameter(description = "수정할 리뷰 id", example = "42") reviewId: Long,
        request: ReviewUpdateRequest,
    ): ResponseEntity<BaseResponse<ReviewResponse>>

    @Operation(
        summary = "리뷰 삭제",
        description = "본인 리뷰를 소프트 삭제한다. 삭제된 리뷰는 목록·평점 집계에서 즉시 제외된다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 성공"),
            ApiResponse(responseCode = "400", description = "미존재/이미 삭제된 리뷰(REVIEW-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "403", description = "타인 리뷰(REVIEW-002)"),
        ],
    )
    fun remove(
        memberId: Long,
        @Parameter(description = "삭제할 리뷰 id", example = "42") reviewId: Long,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "리뷰 좋아요 등록/취소",
        description = """
            리뷰 좋아요 상태를 지정한다. liked=true 는 등록, liked=false 는 취소.
            회원당 리뷰 하나에 좋아요는 1개만 유지되며, 같은 상태로 다시 요청해도 성공(멱등)한다.
            등록(liked=true)은 리뷰가 존재해야 하고, 취소(liked=false)는 좋아요가 없어도 성공한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "처리 성공(같은 상태 재요청 포함)"),
            ApiResponse(responseCode = "400", description = "liked 파라미터 누락, 등록 시 미존재/삭제된 리뷰(REVIEW-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun like(
        memberId: Long,
        @Parameter(description = "대상 리뷰 id", example = "42") reviewId: Long,
        @Parameter(description = "지정할 좋아요 상태 — true 등록, false 취소", example = "true") liked: Boolean,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "리뷰 목록 (전체·음식별) — 비회원 조회 가능",
        description = """
            리뷰를 최신순 20건 keyset 커서 방식으로 조회한다. 인증 없이(비회원) 호출할 수 있다.
            foodId 를 주면 그 음식의 리뷰만, 생략하면 서비스 전체 리뷰를 내려준다(리뷰 피드 화면).
            countryCode 를 주면 작성 시점 국적 스냅샷이 정확히 일치하는 리뷰만 내려간다(리뷰가 없는 코드는 빈 목록).
            회원 조회는 본인이 신고한 리뷰·차단한 회원의 리뷰(다른 회원에게는 그대로 노출)가 제외되고, 비회원 조회는 이 조회자별 제외가 적용되지 않는다.
            삭제된 음식의 리뷰는 전원 제외, 탈퇴한 회원의 리뷰는 author=null·authorWithdrawn=true 로 노출된다.
            비회원 조회의 likedByMe 는 항상 false 다. lang 은 비회원도 필수(누락 400)다.
            각 리뷰에는 음식 요약(food — lang 으로 해석한 이름·대표 이미지)이 포함된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "lang 누락, 미존재 음식(FOOD-001), 비정상 커서(FOOD-002)"),
        ],
    )
    fun listReviews(
        memberId: Long?,
        @Parameter(description = "리뷰를 조회할 음식 id — 생략 시 전체 리뷰", example = "1") foodId: Long?,
        @ParameterObject request: ReviewListRequest,
    ): ResponseEntity<BaseResponse<ReviewListPage>>

    @Operation(
        summary = "내 리뷰 목록",
        description = """
            내가 쓴 리뷰를 최신순 20건 keyset 커서 방식으로 조회한다(프로필 탭 진입).
            각 리뷰에는 음식 요약(food — lang 으로 해석한 이름·대표 이미지)이 포함되며, 음식이 삭제됐으면 food 는 null 이다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "lang 누락, 비정상 커서(FOOD-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun listMyReviews(
        memberId: Long,
        @ParameterObject request: ReviewPageRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>>
}
