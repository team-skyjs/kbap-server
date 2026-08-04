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
@RequestMapping(ApiPaths.ADMIN + "/foods/reviews")
class AdminFoodReviewController(
    private val adminFoodReviewService: AdminFoodReviewService,
) : AdminFoodReviewApi {
    @GetMapping
    override fun getReviewTargets(
        @RequestParam(defaultValue = "${AdminFoodReviewService.DEFAULT_REVIEW_TARGETS}") limit: Int,
    ): ResponseEntity<BaseResponse<AdminFoodReviewTargetsResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodReviewService.getReviewTargets(limit)))

    @PostMapping("/{foodId}")
    override fun applyReviewResult(
        @PathVariable foodId: Long,
        @Valid @RequestBody request: AdminFoodReviewResultRequest,
    ): ResponseEntity<BaseResponse<AdminFoodReviewResultResponse>> {
        val result = adminFoodReviewService.applyReviewResult(
            foodId = foodId,
            passed = request.passed!!,
            rejectedFields = request.rejectedFields.orEmpty(),
            reason = request.reason,
        )
        return ResponseEntity.ok(BaseResponse.ok(result))
    }
}
