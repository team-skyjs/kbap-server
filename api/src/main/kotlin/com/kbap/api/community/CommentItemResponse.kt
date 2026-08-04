package com.kbap.api.community

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "댓글 목록 항목 — 최상위 댓글과 그 대댓글 전량(등록순)")
data class CommentItemResponse(
    @field:Schema(description = "댓글 id", example = "10")
    val commentId: Long,

    @field:Schema(description = "작성자 표시 정보")
    val author: CommentAuthorResponse,

    @field:Schema(description = "본문", example = "정말 맛있죠")
    val content: String,

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,

    @field:Schema(description = "대댓글 목록(등록순, 없으면 빈 배열)")
    val replies: List<CommentReplyResponse>,
)

@Schema(description = "대댓글 항목")
data class CommentReplyResponse(
    @field:Schema(description = "댓글 id", example = "55")
    val commentId: Long,

    @field:Schema(description = "작성자 표시 정보")
    val author: CommentAuthorResponse,

    @field:Schema(description = "본문", example = "저도 먹어봤는데 최고예요 @먹보")
    val content: String,

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,
)

@Schema(description = "댓글 작성자 표시 정보 — 탈퇴한 작성자는 memberId null + \"탈퇴한 사용자\" 로 익명화된다")
data class CommentAuthorResponse(
    @field:Schema(description = "작성자 회원 id(탈퇴한 작성자는 null)", example = "7", nullable = true)
    val memberId: Long?,

    @field:Schema(description = "닉네임(미설정이면 null, 탈퇴한 작성자는 \"탈퇴한 사용자\")", example = "먹보", nullable = true)
    val nickname: String?,

    @field:Schema(description = "프로필 이미지 URL(없으면 null — 클라이언트가 기본 아바타 표시)", nullable = true)
    val profileImageUrl: String?,
)
