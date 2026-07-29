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

@Tag(name = "리뷰", description = "음식 리뷰 작성·수정·삭제 API")
@SecurityRequirement(name = "bearerAuth")
interface ReviewApi {
    @Operation(
        summary = "리뷰 작성",
        description = """
            음식에 리뷰를 작성한다. 별점(1~5)은 필수, 본문(최대 1000자)·사진(최대 3장)은 옵션이다.
            사진은 REVIEW 용도 presigned 업로드를 완료한 본인 소유 경로만 허용한다.
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
        description = "본인 리뷰의 별점·본문·사진을 수정한다. content·imagePaths 는 보낸 값으로 전량 교체된다(생략 시 제거). 작성 시점 국적 스냅샷은 바뀌지 않는다.",
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
        summary = "음식별 리뷰 목록",
        description = """
            음식의 리뷰를 최신순 20건 keyset 커서 방식으로 조회한다.
            countryCode 를 주면 작성 시점 국적 스냅샷이 정확히 일치하는 리뷰만 내려간다(리뷰가 없는 코드는 빈 목록).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "미존재 음식(FOOD-001), 비정상 커서(FOOD-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun listFoodReviews(
        memberId: Long?,
        @Parameter(description = "리뷰를 조회할 음식 id", example = "1") foodId: Long,
        @ParameterObject request: ReviewListRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>>

    @Operation(
        summary = "내 리뷰 목록",
        description = "내가 쓴 리뷰를 최신순 20건 keyset 커서 방식으로 조회한다(프로필 탭 진입).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "비정상 커서(FOOD-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun listMyReviews(
        memberId: Long,
        @ParameterObject request: MyReviewListRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>>
}
