package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/human-reviews", version = "1.0+")
class AdminHumanReviewController(
    private val humanReviewService: AdminHumanReviewService,
) : AdminHumanReviewApi {
    @GetMapping
    override fun getHumanReviews(
        @RequestParam(required = false) adminId: Long?,
        @RequestParam(required = false) cursor: Long?,
    ): ResponseEntity<BaseResponse<AdminHumanReviewListResponse>> =
        ResponseEntity.ok(BaseResponse.ok(humanReviewService.getHumanReviewPage(adminId, cursor)))
}
