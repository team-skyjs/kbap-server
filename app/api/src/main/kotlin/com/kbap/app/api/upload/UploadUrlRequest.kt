package com.kbap.app.api.upload

import com.kbap.application.upload.dto.ImageUploadInput
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

@Schema(description = "업로드용 presigned URL 발급 요청")
data class UploadUrlRequest(
    @field:NotBlank(message = "purpose 는 필수입니다")
    @field:Schema(description = "업로드 용도", example = "MENU_SCAN", requiredMode = Schema.RequiredMode.REQUIRED)
    val purpose: String?,

    @field:NotBlank(message = "contentType 은 필수입니다")
    @field:Schema(description = "업로드 이미지 Content-Type", example = "image/jpeg", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentType: String?,

    @field:NotNull(message = "contentLength 는 필수입니다")
    @field:Positive(message = "contentLength 는 0보다 커야 합니다")
    @field:Schema(description = "업로드 이미지 바이트 수(정확값)", example = "384512", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentLength: Long?,
) {
    fun toInput(memberId: Long): ImageUploadInput =
        ImageUploadInput(
            memberId = memberId,
            purpose = purpose!!,
            contentType = contentType!!,
            contentLength = contentLength!!,
        )
}
