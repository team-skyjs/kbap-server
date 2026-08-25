package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 커뮤니티", description = "게시글 목록·댓글 트리(삭제 포함)·게시글/댓글 블라인드(소프트 삭제)")
@SecurityRequirement(name = "bearerAuth")
interface AdminCommunityApi {
    @Operation(summary = "게시글 목록", description = "최신순. `q` 본문 포함 검색, `memberId` 작성자 필터. 댓글 수·신고 수 동봉.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getPosts(@Parameter(description = "본문 검색어") q: String?, memberId: Long?, page: Int, size: Int): ResponseEntity<BaseResponse<AdminPostPageResponse>>

    @Operation(summary = "게시글 댓글 트리", description = "삭제된 댓글도 `deleted:true` 로 포함한 1depth 트리(최상위 댓글 → `replies`).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "400", description = "없는 게시글(COMMUNITY-001)")])
    fun getComments(postId: Long): ResponseEntity<BaseResponse<AdminCommentTreeResponse>>

    @Operation(summary = "게시글 블라인드", description = "소프트 삭제 — 사용자 피드에서 사라진다(복구 API 없음).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "삭제"), ApiResponse(responseCode = "400", description = "없는 게시글(COMMUNITY-001)")])
    fun deletePost(postId: Long, adminId: Long): ResponseEntity<BaseResponse<AdminContentDeleteResponse>>

    @Operation(summary = "댓글 블라인드", description = "소프트 삭제 — 최상위 댓글이면 대댓글도 함께 숨긴다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "삭제"), ApiResponse(responseCode = "400", description = "없는 댓글(COMMUNITY-006)")])
    fun deleteComment(commentId: Long, adminId: Long): ResponseEntity<BaseResponse<AdminContentDeleteResponse>>
}
