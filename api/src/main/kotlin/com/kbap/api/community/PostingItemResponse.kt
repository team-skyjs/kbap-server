package com.kbap.api.community

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "커뮤니티 피드 항목이자 글 상세 응답")
data class PostingItemResponse(
    @field:Schema(description = "게시글 id", example = "42")
    val postId: Long,

    @field:Schema(description = "작성자 표시 정보")
    val author: PostingAuthorResponse,

    @field:Schema(description = "본문", example = "오늘 김치찌개 최고였다")
    val content: String,

    @field:Schema(description = "사진 URL 목록(없으면 빈 배열). 첫 장이 피드 커버.")
    val imageUrls: List<String>,

    @field:Schema(description = "음식 태그 목록(없으면 빈 배열)")
    val foodTags: List<PostingFoodTagResponse>,

    @field:Schema(description = "좋아요 수(리액션 도입 전까지 0)", example = "0")
    val likeCount: Int,

    @field:Schema(description = "싫어요 수(리액션 도입 전까지 0)", example = "0")
    val dislikeCount: Int,

    @field:Schema(description = "댓글 수(댓글 도입 전까지 0)", example = "0")
    val commentCount: Int,

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,
)

@Schema(description = "게시글 작성자 표시 정보 — 탈퇴 회원이면 memberId 가 null 이고 닉네임·프로필이 익명 표기로 대체된다")
data class PostingAuthorResponse(
    @field:Schema(description = "작성자 회원 id(탈퇴 회원이면 null)", example = "7", nullable = true)
    val memberId: Long?,

    @field:Schema(description = "닉네임(탈퇴 회원이면 \"탈퇴한 사용자\")", example = "먹보", nullable = true)
    val nickname: String?,

    @field:Schema(description = "프로필 이미지 URL(없거나 탈퇴 회원이면 null — 클라이언트가 기본 아바타 표시)", nullable = true)
    val profileImageUrl: String?,
)

@Schema(description = "게시글 음식 태그")
data class PostingFoodTagResponse(
    @field:Schema(description = "음식 id", example = "12")
    val foodId: Long,

    @field:Schema(description = "요청 언어 기준 음식명(번역 부재 시 한국어)", example = "Kimchi Stew")
    val name: String,
)
