package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import com.kbap.common.domain.review.AdminReviewFilter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/reviews", version = "1.0+")
class AdminReviewController(
    private val adminReviewService: AdminReviewService,
) : AdminReviewApi {
    @GetMapping
    override fun getReviews(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) foodId: Long?,
        @RequestParam(required = false) reported: Boolean?,
        @RequestParam(required = false) hasImage: Boolean?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminReviewPageResponse>> {
        val filter = AdminReviewFilter(q = q, memberId = memberId, foodId = foodId, reported = reported, hasImage = hasImage)
        return ResponseEntity.ok(BaseResponse.ok(adminReviewService.getReviewPage(filter, AdminPaging.page(page), AdminPaging.size(size))))
    }

    @DeleteMapping("/{id}")
    override fun deleteReview(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminReviewDeleteResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminReviewService.deleteReview(adminId, id)))

    @PatchMapping("/{id}/images")
    override fun removeImages(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminReviewResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminReviewService.removeImages(adminId, id)))
}
