package com.kbap.api.scan

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "메뉴판 사진 스캔 v2 요청 — 검증된 이미지 경로만 보낸다(OCR 은 서버가 수행).")
data class ScanV2Request(
    @field:NotBlank(message = "imagePath 는 필수입니다")
    @field:Size(max = 512, message = "imagePath 는 최대 512자입니다")
    @field:Pattern(regexp = "^(?!https?://).*", message = "imagePath 는 전체 URL 이 아닌 오브젝트 경로여야 합니다")
    @field:Schema(
        description = "스캔할 메뉴판 사진의 오브젝트 경로(CDN 도메인 제외). 업로드 완료 신고가 검증한 본인 소유 이미지여야 한다.",
        example = "scan/123/20260715-abc123.jpg",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val imagePath: String?,
)
