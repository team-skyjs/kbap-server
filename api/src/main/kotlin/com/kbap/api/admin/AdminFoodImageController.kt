package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import jakarta.validation.Valid
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.RequestHeader

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodImageController(
    private val adminFoodImageService: AdminFoodImageService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminFoodImageApi {
    @PostMapping("/{foodId}/photos/presign")
    override fun presignPhoto(
        @PathVariable foodId: Long,
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminFoodPhotoPresignRequest,
    ): ResponseEntity<BaseResponse<AdminFoodPhotoPresignResponse>> {
        if (target != environment) throw BusinessException(ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
        val upload = adminFoodImageService.presignPhoto(foodId, request.contentType!!, request.contentLength!!, request.contentSha256!!)
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodPhotoPresignResponse(upload.uploadUrl, upload.requiredHeaders, upload.publicUrl, upload.objectKey, upload.expiresAt)))
    }

    @PostMapping("/{foodId}/photos/confirm")
    override fun confirmPhoto(
        @PathVariable foodId: Long,
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminFoodPhotoConfirmRequest,
    ): ResponseEntity<BaseResponse<AdminFoodImageGalleryResponse>> {
        if (target != environment) throw BusinessException(ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
        val result = adminFoodImageService.confirmPhoto(foodId, request.key!!, request.setPrimary == true)
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodImageGalleryResponse.from(result)))
    }

    @GetMapping("/{foodId}/images")
    override fun getGallery(
        @PathVariable foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodImageGalleryResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminFoodImageGalleryResponse.from(adminFoodImageService.getGallery(foodId))))

    @PutMapping("/{foodId}/images/{imageId}/primary")
    override fun setPrimary(
        @PathVariable foodId: Long,
        @PathVariable imageId: Long,
        @Valid @RequestBody request: AdminFoodImagePrimaryRequest,
    ): ResponseEntity<BaseResponse<AdminFoodImageGalleryResponse>> {
        val result = try {
            adminFoodImageService.setPrimary(foodId, imageId, request.version!!)
        } catch (e: OptimisticLockingFailureException) {
            throw BusinessException(ErrorCode.FOOD_VERSION_CONFLICT)
        }
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodImageGalleryResponse.from(result)))
    }

    @PostMapping("/{foodId}/images/generate")
    override fun generateAdditionalImage(
        @PathVariable foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodImageRegenerateResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminFoodImageRegenerateResponse.from(adminFoodImageService.generateAdditionalImage(foodId))),
        )

    @PostMapping("/{foodId}/regenerate-image")
    override fun regenerateImage(
        @PathVariable foodId: Long,
        @Valid @RequestBody(required = false) request: AdminFoodImageRegenerateRequest?,
    ): ResponseEntity<BaseResponse<AdminFoodImageRegenerateResponse>> {
        val result = adminFoodImageService.regenerateImage(foodId, request?.intent?.domain, request?.reason)
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodImageRegenerateResponse.from(result)))
    }
}

@Schema(description = "음식 실물 사진 업로드 URL 발급 요청")
data class AdminFoodPhotoPresignRequest(
    @field:NotBlank(message = "contentType 은 필수입니다")
    @field:Schema(description = "image/png · image/jpeg · image/webp", example = "image/jpeg", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentType: String? = null,
    @field:NotNull(message = "contentLength 는 필수입니다")
    @field:Positive(message = "contentLength 는 1 이상이어야 합니다")
    @field:Schema(description = "올릴 파일 바이트 수(서명에 들어가므로 실제 PUT 본문과 같아야 한다, 최대 5MB)", example = "812345", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentLength: Long? = null,
    @field:NotBlank(message = "contentSha256 는 필수입니다")
    @field:Schema(description = "올릴 파일의 SHA-256(소문자 hex 64자) — 앞 16자가 키에 들어가고, S3 가 PUT 본문 해시를 이 값으로 검사한다", requiredMode = Schema.RequiredMode.REQUIRED)
    val contentSha256: String? = null,
)

@Schema(description = "음식 실물 사진 업로드 URL")
data class AdminFoodPhotoPresignResponse(
    @field:Schema(description = "PUT 할 presigned URL(10분 유효)")
    val uploadUrl: String,
    @field:Schema(description = "PUT 에 그대로 실어야 하는 헤더(Content-Type·Content-Length·체크섬)")
    val requiredHeaders: Map<String, String>,
    @field:Schema(description = "올린 뒤 공개(CDN) URL")
    val publicUrl: String,
    @field:Schema(description = "오브젝트 키 `{prefix}/images/foods/{음식 id}/{sha256 앞 16자}.{png|jpg|webp}` — confirm 에 그대로 보낸다", example = "dev/images/foods/1201/3a7bd3e2360a3d29.jpg")
    val key: String,
    val expiresAt: java.time.Instant,
)

@Schema(description = "음식 실물 사진 저장 요청")
data class AdminFoodPhotoConfirmRequest(
    @field:NotBlank(message = "key 는 필수입니다")
    @field:Schema(description = "presign 응답의 key", requiredMode = Schema.RequiredMode.REQUIRED)
    val key: String? = null,
    @field:Schema(description = "true 면 이 사진을 대표(image_ref)로 바꾼다 — 기본 false", example = "true", nullable = true)
    val setPrimary: Boolean? = null,
)
