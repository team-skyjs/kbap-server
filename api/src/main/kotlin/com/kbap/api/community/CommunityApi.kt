package com.kbap.api.community

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "커뮤니티", description = "커뮤니티 게시글 작성·수정·삭제 API")
@SecurityRequirement(name = "bearerAuth")
interface CommunityApi {
    @Operation(
        summary = "게시글 작성",
        description = """
            커뮤니티에 게시글을 작성한다. 제목은 없고 본문(최대 2000자)이 곧 글이다.
            사진은 최대 4장이며 첫 장이 피드 커버가 된다 — COMMUNITY 용도 presigned 업로드를 완료한 본인 소유 경로만 허용한다.
            음식 태그는 최대 3개이며 서비스에 등록된(READY) 음식만 태그할 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "검증 실패(본문 길이·사진 수·태그 수), 미소유 이미지(COMMUNITY-003), 태그 불가 음식(COMMUNITY-004)",
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun create(memberId: Long, request: CommunityCreateRequest): ResponseEntity<BaseResponse<CommunityPostingResponse>>

    @Operation(
        summary = "게시글 수정",
        description = """
            본인 게시글의 본문·사진·음식 태그를 수정한다. 세 값 모두 보낸 값으로 전량 교체된다(생략 시 제거).
            수정 사실은 다른 사용자에게 표시하지 않지만 수정 시각(editedAt)은 기록된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "미존재/삭제된 게시글(COMMUNITY-001), 검증 실패, 미소유 이미지(COMMUNITY-003), 태그 불가 음식(COMMUNITY-004)",
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "403", description = "타인 게시글(COMMUNITY-002)"),
        ],
    )
    fun update(
        memberId: Long,
        postId: Long,
        request: CommunityUpdateRequest,
    ): ResponseEntity<BaseResponse<CommunityPostingResponse>>

    @Operation(
        summary = "게시글 삭제",
        description = "본인 게시글을 삭제한다(소프트 삭제). 삭제된 글은 이후 조회에 노출되지 않으며, 글에 달린 댓글도 함께 보이지 않는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 성공"),
            ApiResponse(responseCode = "400", description = "미존재/이미 삭제된 게시글(COMMUNITY-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "403", description = "타인 게시글(COMMUNITY-002)"),
        ],
    )
    fun remove(memberId: Long, postId: Long): ResponseEntity<BaseResponse<Unit>>
}
