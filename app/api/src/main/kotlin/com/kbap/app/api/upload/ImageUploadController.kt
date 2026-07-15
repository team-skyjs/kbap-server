package com.kbap.app.api.upload

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberId
import com.kbap.application.upload.ImageUploadApplicationService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/images")
class ImageUploadController(
    private val imageUploadApplicationService: ImageUploadApplicationService,
) : ImageUploadApi {
    @PostMapping("/upload-url")
    override fun issueUploadUrl(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: UploadUrlRequest,
    ): ResponseEntity<BaseResponse<UploadUrlResponse>> {
        val upload = imageUploadApplicationService.issueUploadUrl(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(UploadUrlResponse.from(upload)))
    }
}
