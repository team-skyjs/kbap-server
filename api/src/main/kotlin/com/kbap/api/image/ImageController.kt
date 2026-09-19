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
class ImageController(
    private val imageUploadService: ImageUploadService,
) : ImageApi {
    @PostMapping("/complete")
    override fun complete(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @Valid @RequestBody request: ImageCompleteRequest,
    ): ResponseEntity<BaseResponse<ImageCompleteResponse>> {
        val image = imageUploadService.completeUpload(memberId, installationId, request.path!!, request.contentType!!, request.size!!)
        return ResponseEntity.ok(BaseResponse.ok(ImageCompleteResponse.from(image)))
    }
}
