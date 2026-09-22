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

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodImageController(
    private val adminFoodImageService: AdminFoodImageService,
) : AdminFoodImageApi {
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

    @PostMapping("/{foodId}/regenerate-image")
    override fun regenerateImage(
        @PathVariable foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodImageRegenerateResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminFoodImageRegenerateResponse.from(adminFoodImageService.regenerateImage(foodId))),
        )
}
