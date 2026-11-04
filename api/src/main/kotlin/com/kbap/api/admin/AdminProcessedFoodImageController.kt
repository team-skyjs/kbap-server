package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.processedfood.model.ProcessedFoodImage
import com.kbap.common.domain.processedfood.model.ProcessedFoodImageKind
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/{id}/images", version = "1.0+")
class AdminProcessedFoodImageController(
    private val imageService: AdminProcessedFoodImageService,
) : AdminProcessedFoodImageApi {
    @GetMapping
    override fun getImages(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>> =
        ResponseEntity.ok(BaseResponse.ok(imageService.getImages(id)))

    @PostMapping("/presign")
    override fun presign(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodImagePresignRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutPresignResponse>> =
        ResponseEntity.ok(BaseResponse.ok(imageService.presign(id, request.contentType!!, request.contentLength!!, request.contentSha256!!)))

    @PostMapping("/confirm")
    override fun confirm(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodImageConfirmRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>> =
        ResponseEntity.ok(BaseResponse.ok(imageService.confirm(id, request.key!!, request.kind!!)))

    @PutMapping("/order")
    override fun reorder(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodImageOrderRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>> =
        ResponseEntity.ok(BaseResponse.ok(imageService.reorder(id, request.imageIds!!)))

    @DeleteMapping("/{imageId}")
    override fun delete(
        @PathVariable id: Long,
        @PathVariable imageId: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>> =
        ResponseEntity.ok(BaseResponse.ok(imageService.deleteImage(id, imageId)))
}

@Schema(description = "가공식품 사진 업로드 URL 발급 요청")
data class AdminProcessedFoodImagePresignRequest(
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

@Schema(description = "가공식품 사진 저장 요청")
data class AdminProcessedFoodImageConfirmRequest(
    @field:NotBlank(message = "key 는 필수입니다")
    @field:Schema(description = "presign 응답의 key", requiredMode = Schema.RequiredMode.REQUIRED)
    val key: String? = null,
    @field:NotNull(message = "kind 는 필수입니다")
    @field:Schema(description = "사진 종류 — CUTOUT(누끼)·FRONT(앞면)·TOP(윗면)·LABEL(성분표)·OTHER", example = "FRONT", requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: ProcessedFoodImageKind? = null,
)

@Schema(description = "가공식품 사진 순서 변경 요청")
data class AdminProcessedFoodImageOrderRequest(
    @field:NotNull(message = "imageIds 는 필수입니다")
    @field:Schema(description = "이 상품의 사진 id 전부를 원하는 순서로 — 빠지거나 남는 id·중복이 있으면 COMMON-002", example = "[12, 10, 11]", requiredMode = Schema.RequiredMode.REQUIRED)
    val imageIds: List<Long>? = null,
)

@Schema(description = "가공식품 사진 목록(표시 순서)")
data class AdminProcessedFoodImagesResponse(
    val processedFoodId: Long,
    val images: List<AdminProcessedFoodImageItem>,
)

data class AdminProcessedFoodImageItem(
    val id: Long,
    val url: String,
    val kind: ProcessedFoodImageKind,
    val sortOrder: Int,
) {
    companion object {
        fun from(image: ProcessedFoodImage) = AdminProcessedFoodImageItem(image.id, image.imageUrl, image.kind, image.sortOrder)
    }
}
