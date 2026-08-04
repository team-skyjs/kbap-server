package com.kbap.api.community

import com.kbap.common.domain.community.model.Posting
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "커뮤니티 게시글 단건 응답")
data class CommunityPostingResponse(
    @field:Schema(description = "게시글 id", example = "42")
    val postId: Long,

    @field:Schema(description = "작성자 회원 id", example = "7")
    val memberId: Long,

    @field:Schema(description = "본문", example = "오늘 김치찌개 최고였다")
    val content: String,

    @field:Schema(description = "사진 URL 목록(없으면 빈 배열). 첫 장이 피드 커버.")
    val imageUrls: List<String>,

    @field:Schema(description = "태그된 음식 id 목록(없으면 빈 배열)")
    val foodIds: List<Long>,

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,

    @field:Schema(description = "수정 시각(수정한 적 없으면 null)", nullable = true)
    val editedAt: LocalDateTime?,
) {
    companion object {
        fun from(posting: Posting, imagePublicBaseUrl: String): CommunityPostingResponse =
            CommunityPostingResponse(
                postId = posting.id,
                memberId = posting.memberId,
                content = posting.content,
                imageUrls = posting.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                foodIds = posting.foodIds.orEmpty(),
                createdAt = posting.createdAt,
                editedAt = posting.editedAt,
            )
    }
}
