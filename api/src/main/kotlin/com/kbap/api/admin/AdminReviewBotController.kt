package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountService
import com.kbap.api.reviewbot.ReviewBotPhotoService
import com.kbap.api.reviewbot.ReviewBotPhotoInventory
import com.kbap.api.reviewbot.ReviewBotPhotoSyncResult
import org.springframework.web.bind.annotation.GetMapping
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/review-bots", version = "1.0+")
class AdminReviewBotController(
    private val accountService: ReviewBotAccountService,
    private val photos: ReviewBotPhotoService,
) : AdminReviewBotApi {
    @PostMapping
    override fun ensureBots(
        @Valid @RequestBody request: AdminReviewBotRequest,
    ): ResponseEntity<BaseResponse<AdminReviewBotResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminReviewBotResponse.from(accountService.ensureBots(request.count!!))))

    @GetMapping("/images")
    override fun getPhotoInventory(): ResponseEntity<BaseResponse<ReviewBotPhotoInventory>> = ResponseEntity.ok(BaseResponse.ok(photos.getInventory()))

    @PostMapping("/images/sync")
    override fun syncPhotos(): ResponseEntity<BaseResponse<ReviewBotPhotoSyncResult>> = ResponseEntity.ok(BaseResponse.ok(photos.sync()))
}
