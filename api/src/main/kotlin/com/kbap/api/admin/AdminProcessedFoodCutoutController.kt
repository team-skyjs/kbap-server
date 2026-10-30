package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/{id}/cutout-image", version = "1.0+")
class AdminProcessedFoodCutoutController(
    private val cutoutService: AdminProcessedFoodCutoutService,
) : AdminProcessedFoodCutoutApi {
    @PostMapping("/presign")
    override fun presign(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodCutoutPresignRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutPresignResponse>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.presign(id, request.contentLength!!)))

    @PostMapping("/confirm")
    override fun confirm(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodCutoutConfirmRequest,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutResponse>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.confirm(id, request.key!!, adminId)))

    @DeleteMapping
    override fun delete(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutResponse>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.deleteCutout(id)))
}

@Schema(description = "누끼 이미지 업로드 URL 발급 요청")
data class AdminProcessedFoodCutoutPresignRequest(
    @field:NotNull(message = "contentLength 는 필수입니다")
    @field:Positive(message = "contentLength 는 1 이상이어야 합니다")
    @field:Schema(description = "올릴 PNG 의 바이트 수(서명에 들어가므로 실제 PUT 본문과 같아야 한다, 최대 2MB)", example = "183204", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentLength: Long? = null,
)

@Schema(description = "누끼 이미지 업로드 URL")
data class AdminProcessedFoodCutoutPresignResponse(
    @field:Schema(description = "PUT 할 presigned URL(10분 유효)")
    val uploadUrl: String,
    @field:Schema(description = "PUT 에 그대로 실어야 하는 헤더(Content-Type: image/png, Content-Length)")
    val requiredHeaders: Map<String, String>,
    @field:Schema(description = "올린 뒤 공개(CDN) URL")
    val publicUrl: String,
    @field:Schema(description = "오브젝트 키 — confirm 에 그대로 보낸다", example = "dev/processed/cutout/20150445222185.png")
    val key: String,
    val expiresAt: Instant,
)

@Schema(description = "누끼 이미지 업로드 확인 요청")
data class AdminProcessedFoodCutoutConfirmRequest(
    @field:NotBlank(message = "key 는 필수입니다")
    @field:Schema(description = "presign 응답의 key", requiredMode = Schema.RequiredMode.REQUIRED)
    val key: String? = null,
)

@Schema(description = "가공식품 누끼 이미지 상태")
data class AdminProcessedFoodCutoutResponse(
    val processedFoodId: Long,
    @field:Schema(description = "저장된 누끼 URL(캐시 무효화용 ?v=수정시각) — 없으면 null", nullable = true)
    val cutoutImageUrl: String?,
    @field:Schema(description = "앱이 받는 imageUrl — 승인된 누끼가 있으면 누끼, 아니면 HACCP 원본", nullable = true)
    val imageUrl: String?,
)
