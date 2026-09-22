package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.api.feedback.FeedbackReplyCreateRequest
import com.kbap.api.feedback.FeedbackStatusUpdateRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/feedbacks", version = "1.0+")
class AdminFeedbackController(
    private val adminFeedbackService: AdminFeedbackService,
) : AdminFeedbackApi {
    @GetMapping
    override fun getFeedbacks(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<BaseResponse<AdminFeedbackPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminFeedbackPageResponse.from(
                    adminFeedbackService.getFeedbackPage(
                        status = status,
                        page = page?.coerceAtLeast(0) ?: 0,
                        size = size?.coerceIn(1, AdminFeedbackService.DEFAULT_PAGE_SIZE)
                            ?: AdminFeedbackService.DEFAULT_PAGE_SIZE,
                    ),
                ),
            ),
        )

    @GetMapping("/{id}")
    override fun getFeedback(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminFeedbackDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminFeedbackDetailResponse.from(adminFeedbackService.getFeedback(id))))

    @PostMapping("/{id}/replies")
    override fun reply(
        @PathVariable id: Long,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
        @Valid @RequestBody request: FeedbackReplyCreateRequest,
    ): ResponseEntity<BaseResponse<AdminFeedbackReplyResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            BaseResponse.ok(AdminFeedbackReplyResponse.from(adminFeedbackService.reply(id, adminAccountId, request.content))),
        )

    @PatchMapping("/{id}/status")
    override fun changeStatus(
        @PathVariable id: Long,
        @Valid @RequestBody request: FeedbackStatusUpdateRequest,
    ): ResponseEntity<BaseResponse<AdminFeedbackStatusResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminFeedbackStatusResponse.from(adminFeedbackService.changeStatus(id, request.status))),
        )
}
