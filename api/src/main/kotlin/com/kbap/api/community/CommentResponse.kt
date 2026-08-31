package com.kbap.api.community

import com.kbap.common.domain.community.model.Comment
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "댓글 작성/수정 결과")
data class CommentResponse(
    @field:Schema(description = "댓글 id", example = "55")
    val commentId: Long,

    @field:Schema(description = "소속 게시글 id", example = "42")
    val postId: Long,

    @field:Schema(description = "최상위 댓글 id(대댓글일 때만, 최상위 댓글이면 null)", example = "10", nullable = true)
    val parentCommentId: Long?,

    @field:Schema(description = "본문", example = "저도 먹어봤는데 최고예요 @먹보")
    val content: String,

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,

    @field:Schema(description = "수정 시각(수정한 적 없으면 null)", nullable = true)
    val editedAt: LocalDateTime?,
) {
    companion object {
        fun from(comment: Comment): CommentResponse =
            CommentResponse(
                commentId = comment.id,
                postId = comment.postId,
                parentCommentId = comment.parentId,
                content = comment.content,
                createdAt = comment.createdAt,
                editedAt = comment.editedAt,
            )
    }
}
