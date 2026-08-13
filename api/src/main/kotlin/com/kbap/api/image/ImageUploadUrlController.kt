package com.kbap.api.image

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/images")
class ImageUploadUrlController(
    private val imageUploadApplicationService: PresignedUploadService,
) : ImageUploadUrlApi {
    @PostMapping("/upload-url")
    override fun issueUploadUrl(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: UploadUrlRequest,
    ): ResponseEntity<BaseResponse<UploadUrlResponse>> {
        val upload = imageUploadApplicationService.issueUploadUrl(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(UploadUrlResponse.from(upload)))
    }
}
