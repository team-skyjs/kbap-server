package com.kbap.api.feedback

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberIdOrNull
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/feedbacks")
class FeedbackController(
    private val feedbackService: FeedbackService,
) : FeedbackApi {
    @PostMapping
    override fun create(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) userAgent: String?,
        @Valid @RequestBody request: FeedbackCreateRequest,
    ): ResponseEntity<BaseResponse<FeedbackCreateResponse>> {
        val result = feedbackService.createFeedback(
            memberId = memberId,
            installationId = installationId,
            content = request.content,
            imagePaths = request.imagePaths,
            deviceInfo = request.deviceInfo,
            userAgent = userAgent,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(BaseResponse.ok(FeedbackCreateResponse.from(result)))
    }

    @GetMapping("/me")
    override fun getMine(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<BaseResponse<MyFeedbackPageResponse>> {
        val page = feedbackService.getMyFeedbackPage(
            memberId = memberId,
            installationId = installationId,
            cursor = cursor,
            size = size?.coerceIn(1, FeedbackService.PAGE_SIZE) ?: FeedbackService.PAGE_SIZE,
        )
        return ResponseEntity.ok(BaseResponse.ok(MyFeedbackPageResponse.from(page)))
    }
}
