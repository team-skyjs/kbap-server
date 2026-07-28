package com.kbap.api.image

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@Schema(description = "업로드 완료 신고 요청 — 서명 URL 업로드를 마친 이미지의 경로·신고 형식·크기")
data class ImageCompleteRequest(
    @field:NotBlank(message = "path 는 필수입니다")
    @field:Size(max = 512, message = "path 는 최대 512자입니다")
    @field:Pattern(regexp = "^(?!https?://).*", message = "path 는 전체 URL 이 아닌 오브젝트 경로여야 합니다")
    @field:Schema(
        description = "업로드한 오브젝트의 경로(CDN 도메인 제외). 발급 시 받은 객체 키를 그대로 전달한다.",
        example = "scan/123/20260715-abc123.jpg",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val path: String?,

    @field:NotBlank(message = "contentType 은 필수입니다")
    @field:Schema(description = "업로드 시 신고한 Content-Type", example = "image/jpeg", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentType: String?,

    @field:NotNull(message = "size 는 필수입니다")
    @field:Positive(message = "size 는 양수여야 합니다")
    @field:Schema(description = "업로드한 파일 크기(bytes)", example = "1048576", requiredMode = Schema.RequiredMode.REQUIRED)
    val size: Long?,
)
