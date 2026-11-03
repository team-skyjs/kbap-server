package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountService
import com.kbap.api.reviewbot.ReviewBotPhotoService
import com.kbap.api.reviewbot.ReviewBotPhotoInventory
import com.kbap.api.reviewbot.ReviewBotPhotoDedupeResult
import com.kbap.api.reviewbot.ReviewBotProcessedFoodComposeResult
import com.kbap.api.reviewbot.ReviewBotProcessedFoodRunResult
import com.kbap.api.reviewbot.ReviewBotRedistributeResult
import com.kbap.api.reviewbot.ReviewBotProcessedFoodService
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
import java.time.ZoneId

@RestController
@RequestMapping(ApiPaths.ADMIN + "/review-bots", version = "1.0+")
class AdminReviewBotController(
    private val accountService: ReviewBotAccountService,
    private val photos: ReviewBotPhotoService,
    private val rewriter: ReviewBotRewriteService,
    private val processedFoodBot: ReviewBotProcessedFoodService,
) : AdminReviewBotApi {
    @PostMapping("/processed-foods/photos/dedupe")
    override fun dedupeProcessedFoodPhotos(
        @RequestHeader("X-Target-Environment") target: String,
        @RequestBody request: AdminReviewBotPhotoDedupeRequest,
    ): ResponseEntity<BaseResponse<ReviewBotPhotoDedupeResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(processedFoodBot.dedupePhotos(target, request.dryRun)))

    @PostMapping("/processed-foods/reviews")
    override fun writeProcessedFoodReviews(
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminReviewBotProcessedFoodRequest,
    ): ResponseEntity<BaseResponse<ReviewBotProcessedFoodRunResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(BaseResponse.ok(processedFoodBot.run(target, request.dryRun, request.limit, request.ids, request.topLines, request.seed, request.spreadDays)))

    @PostMapping("/processed-foods/reviews/compose")
    override fun composeProcessedFoodReview(
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminReviewBotComposeRequest,
    ): ResponseEntity<BaseResponse<ReviewBotProcessedFoodComposeResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(
            BaseResponse.ok(
                processedFoodBot.compose(
                    target,
                    request.productId!!,
                    request.imagePaths!!,
                    request.createdAt?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime(),
                    request.botId,
                    request.rating,
                    request.dryRun,
                ),
            ),
        )

    @PostMapping("/processed-foods/reviews/redistribute")
    override fun redistributeProcessedFoodReviewDates(
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminReviewBotRedistributeRequest,
    ): ResponseEntity<BaseResponse<ReviewBotRedistributeResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(BaseResponse.ok(processedFoodBot.redistribute(target, request.days, request.dryRun, request.seed)))

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
