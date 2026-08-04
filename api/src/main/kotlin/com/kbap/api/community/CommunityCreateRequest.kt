package com.kbap.api.community

import com.kbap.common.domain.community.model.Posting
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "커뮤니티 게시글 작성 요청")
data class CommunityCreateRequest(
    @field:NotBlank(message = "content 는 필수입니다")
    @field:Size(max = Posting.MAX_CONTENT_LENGTH, message = "본문은 최대 2000자입니다")
    @field:Schema(description = "본문(필수, 최대 2000자)", example = "오늘 김치찌개 최고였다", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String?,

    @field:Size(max = Posting.MAX_IMAGE_COUNT, message = "사진은 최대 4장입니다")
    @field:Schema(description = "업로드 완료된 사진 경로(옵션, 최대 4장). 첫 장이 피드 커버가 된다.")
    val imagePaths: List<String>? = null,

    @field:Size(max = Posting.MAX_FOOD_TAG_COUNT, message = "음식 태그는 최대 3개입니다")
    @field:Schema(description = "태그할 음식 id(옵션, 최대 3개). 등록된 음식만 태그할 수 있다.")
    val foodIds: List<Long>? = null,
)

@Schema(description = "커뮤니티 게시글 수정 요청 — content·imagePaths·foodIds 는 보낸 값으로 전량 교체된다")
data class CommunityUpdateRequest(
    @field:NotBlank(message = "content 는 필수입니다")
    @field:Size(max = Posting.MAX_CONTENT_LENGTH, message = "본문은 최대 2000자입니다")
    @field:Schema(description = "본문(필수, 최대 2000자)", example = "다시 보니 별로였다", requiredMode = Schema.RequiredMode.REQUIRED)
    val content: String?,

    @field:Size(max = Posting.MAX_IMAGE_COUNT, message = "사진은 최대 4장입니다")
    @field:Schema(description = "업로드 완료된 사진 경로(생략 시 사진 제거)")
    val imagePaths: List<String>? = null,

    @field:Size(max = Posting.MAX_FOOD_TAG_COUNT, message = "음식 태그는 최대 3개입니다")
    @field:Schema(description = "태그할 음식 id(생략 시 태그 제거)")
    val foodIds: List<Long>? = null,
)
