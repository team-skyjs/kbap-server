package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods/content-reviews")
class AdminFoodContentReviewController(
    private val adminFoodContentReviewService: AdminFoodContentReviewService,
) : AdminFoodContentReviewApi {
    @GetMapping
    override fun getContentReviewTargets(
        @RequestParam(defaultValue = "${AdminFoodContentReviewService.DEFAULT_CONTENT_REVIEW_TARGETS}") limit: Int,
    ): ResponseEntity<BaseResponse<AdminFoodContentReviewTargetsResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodContentReviewService.getContentReviewTargets(limit)))

    @PostMapping("/{foodId}")
    override fun applyContentReviewResult(
        @PathVariable foodId: Long,
        @Valid @RequestBody request: AdminFoodContentReviewResultRequest,
    ): ResponseEntity<BaseResponse<AdminFoodContentReviewResultResponse>> {
        val result = adminFoodContentReviewService.applyContentReviewResult(
            foodId = foodId,
            passed = request.passed!!,
            rejectedFields = request.rejectedFields.orEmpty(),
            reason = request.reason,
        )
        return ResponseEntity.ok(BaseResponse.ok(result))
    }
}
