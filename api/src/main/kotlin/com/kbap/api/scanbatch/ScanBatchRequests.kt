package com.kbap.api.scanbatch

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "스캔 묶음 생성 요청 — 사진 1~10장(배열 순서 = 선택 순서)")
data class ScanBatchCreateRequest(
    @field:NotBlank(message = "clientRequestId 는 필수입니다")
    @field:Schema(description = "앱이 만든 UUID — 묶음 id 이자 멱등 키(같은 내용 재전송은 같은 묶음, 다른 내용은 409 SCAN-005)", example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10", requiredMode = Schema.RequiredMode.REQUIRED)
    val clientRequestId: String?,

    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = "결과 언어", example = "ko", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String?,

    @field:NotBlank(message = "currency 는 필수입니다")
    @field:Schema(description = "표시 통화(ISO 4217)", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
    val currency: String?,

    @field:NotNull(message = "photos 는 필수입니다")
    @field:Size(min = 1, message = "사진은 1장 이상이어야 합니다")
    @field:Valid
    @field:Schema(description = "사진 1~10장 — 11장 이상은 SCAN-009", requiredMode = Schema.RequiredMode.REQUIRED)
    val photos: List<ScanBatchPhotoRequest>?,
)

@Schema(description = "묶음 사진 한 장")
data class ScanBatchPhotoRequest(
    @field:NotBlank(message = "photoId 는 필수입니다")
    @field:Schema(description = "앱이 만든 사진 UUID(묶음 안에서 유일)", example = "6a1f0e1c-2b3d-4c5e-8f90-0a1b2c3d4e5f", requiredMode = Schema.RequiredMode.REQUIRED)
    val photoId: String?,

    @field:NotBlank(message = "imagePath 는 필수입니다")
    @field:Size(max = 512, message = "imagePath 는 512자 이하여야 합니다")
    @field:Schema(description = "본인이 MENU_SCAN 목적으로 업로드 완료한 오브젝트 경로(묶음 안에서 유일) — 아니면 SCAN-001", example = "dev/images/scans/2026/10/1_550e8400-e29b-41d4-a716-446655440000.jpg", requiredMode = Schema.RequiredMode.REQUIRED)
    val imagePath: String?,
)
