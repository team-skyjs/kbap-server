package com.kbap.api.image

import com.kbap.api.common.ApiPaths
import com.kbap.api.common.BaseResponse
import com.kbap.api.common.auth.AuthMemberId
import com.kbap.domain.image.ImageUploadService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/images")
class ImageController(
    private val imageUploadService: ImageUploadService,
) : ImageApi {
    @PostMapping("/complete")
    override fun complete(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ImageCompleteRequest,
    ): ResponseEntity<BaseResponse<ImageCompleteResponse>> {
        val image = imageUploadService.completeUpload(memberId, request.path!!, request.contentType!!, request.size!!)
        return ResponseEntity.ok(BaseResponse.ok(ImageCompleteResponse.from(image)))
    }
}
