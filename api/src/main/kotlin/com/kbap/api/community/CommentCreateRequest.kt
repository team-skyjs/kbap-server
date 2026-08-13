package com.kbap.api.community

import com.kbap.common.domain.community.model.Comment
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "댓글 작성 요청 — @멘션은 본문 텍스트의 일부로 취급한다(별도 저장 없음)")
data class CommentCreateRequest(
    @field:NotBlank(message = "content 는 필수입니다")
    @field:Size(max = Comment.MAX_CONTENT_LENGTH, message = "본문은 최대 2000자입니다")
    @field:Schema(description = "본문(필수, 최대 2000자)", example = "저도 먹어봤는데 최고예요 @먹보", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String?,

    @field:Schema(description = "답글 대상 댓글 id(옵션). 생략하면 최상위 댓글. 대댓글 id 를 보내면 그 최상위 댓글의 대댓글로 정규화된다(1depth).", nullable = true)
    val parentCommentId: Long? = null,
)

@Schema(description = "댓글 수정 요청 — 본문만 수정할 수 있다(답글 소속 이동 불가)")
data class CommentUpdateRequest(
    @field:NotBlank(message = "content 는 필수입니다")
    @field:Size(max = Comment.MAX_CONTENT_LENGTH, message = "본문은 최대 2000자입니다")
    @field:Schema(description = "본문(필수, 최대 2000자)", example = "다시 보니 더 맛있었다", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String?,
)
