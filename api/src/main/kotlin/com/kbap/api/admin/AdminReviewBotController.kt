package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountService
import com.kbap.api.reviewbot.ReviewBotPhotoSyncService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/review-bots", version = "1.0+")
class AdminReviewBotController(
    private val accountService: ReviewBotAccountService,
    private val photoSyncService: ReviewBotPhotoSyncService,
) : AdminReviewBotApi {
    @PostMapping("/images/sync")
    override fun syncPhotoPool(
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ResponseEntity<BaseResponse<AdminReviewBotPhotoSyncResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminReviewBotPhotoSyncResponse.from(photoSyncService.sync(force))))

    @PostMapping
    override fun ensureBots(
        @Valid @RequestBody request: AdminReviewBotRequest,
    ): ResponseEntity<BaseResponse<AdminReviewBotResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminReviewBotResponse.from(accountService.ensureBots(request.count!!))))
}
