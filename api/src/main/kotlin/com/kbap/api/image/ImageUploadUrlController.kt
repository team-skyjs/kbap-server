package com.kbap.api.image

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.auth.AuthMemberIdOrNull
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/images")
class ImageUploadUrlController(
    private val imageUploadApplicationService: PresignedUploadService,
) : ImageUploadUrlApi {
    @PostMapping("/upload-url")
    override fun issueUploadUrl(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @Valid @RequestBody request: UploadUrlRequest,
    ): ResponseEntity<BaseResponse<UploadUrlResponse>> {
        val upload = imageUploadApplicationService.issueUploadUrl(request.toInput(memberId, installationId))
        return ResponseEntity.ok(BaseResponse.ok(UploadUrlResponse.from(upload)))
    }
}
