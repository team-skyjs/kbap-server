package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 리뷰", description = "리뷰 목록(검색·신고·사진 필터)·관리자 삭제(랭킹 차감)·사진만 제거")
@SecurityRequirement(name = "bearerAuth")
interface AdminReviewApi {
    @Operation(
        summary = "리뷰 목록",
        description = "최신순. `q` 는 숫자면 리뷰 id, 아니면 본문 포함 검색. `reported=true` 는 신고가 1건 이상인 리뷰, `hasImage` 는 사진 유무. 각 항목에 작성자 닉네임·음식명·좋아요·신고 수 동봉.",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getReviews(
        @Parameter(description = "리뷰 id 또는 본문 검색어") q: String?,
        memberId: Long?,
        foodId: Long?,
        @Parameter(description = "신고된 리뷰만/제외") reported: Boolean?,
        @Parameter(description = "사진 있는 리뷰만/제외") hasImage: Boolean?,
        page: Int,
        size: Int,
    ): ResponseEntity<BaseResponse<AdminReviewPageResponse>>

    @Operation(summary = "리뷰 삭제(소프트)", description = "사용자 삭제와 같은 규칙으로 작성자 리뷰 수·고유 음식 수를 차감하고 랭킹 원장(REVIEW_DELETED)을 남긴다. 작성자가 비활성이면 차감 없이 삭제만(`rankingAdjusted:false`).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "삭제"), ApiResponse(responseCode = "400", description = "없는 리뷰(REVIEW-001)")])
    fun deleteReview(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminReviewDeleteResponse>>

    @Operation(summary = "리뷰 사진 제거", description = "본문·별점은 두고 사진만 비운다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "제거 후 리뷰"), ApiResponse(responseCode = "400", description = "없는 리뷰(REVIEW-001)")])
    fun removeImages(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminReviewResponse>>
}
