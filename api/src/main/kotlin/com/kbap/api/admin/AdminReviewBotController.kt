package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountService
import com.kbap.api.reviewbot.ReviewBotPhotoService
import com.kbap.api.reviewbot.ReviewBotPhotoInventory
import com.kbap.api.reviewbot.ReviewBotRewriteResult
import com.kbap.api.reviewbot.ReviewBotRewriteService
import org.springframework.web.bind.annotation.GetMapping
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/review-bots", version = "1.0+")
class AdminReviewBotController(
    private val accountService: ReviewBotAccountService,
    private val photos: ReviewBotPhotoService,
    private val rewriter: ReviewBotRewriteService,
) : AdminReviewBotApi {
    @GetMapping("/images")
    override fun getPhotoInventory(): ResponseEntity<BaseResponse<ReviewBotPhotoInventory>> =
        ResponseEntity.ok(BaseResponse.ok(photos.getInventory()))

    @PostMapping("/images/sync")
    override fun syncPhotoPool(
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ResponseEntity<BaseResponse<AdminReviewBotPhotoSyncResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminReviewBotPhotoSyncResponse.from(photos.sync())))

    @PostMapping("/reviews/rewrite")
    override fun rewriteReviews(
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminReviewBotRewriteRequest,
    ): ResponseEntity<BaseResponse<ReviewBotRewriteResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(BaseResponse.ok(rewriter.rewrite(target, request.ratio!!, request.dryRun, request.limit, request.seed, request.afterId)))

    @PostMapping
    override fun ensureBots(
        @Valid @RequestBody request: AdminReviewBotRequest,
    ): ResponseEntity<BaseResponse<AdminReviewBotResponse>> =
        ResponseEntity.ok(BaseResponse.ok(AdminReviewBotResponse.from(accountService.ensureBots(request.count!!))))
}
