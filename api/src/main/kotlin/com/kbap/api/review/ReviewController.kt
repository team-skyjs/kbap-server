package com.kbap.api.review

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1)
class ReviewController(
    private val reviewService: ReviewService,
) : ReviewApi {
    @PostMapping("/reviews")
    override fun create(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ReviewCreateRequest,
    ): ResponseEntity<BaseResponse<ReviewResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                reviewService.createReview(
                    memberId = memberId,
                    foodId = request.foodId!!,
                    rating = request.rating!!,
                    content = request.content,
                    imagePaths = request.imagePaths,
                ),
            ),
        )

    @PatchMapping("/reviews/{reviewId}")
    override fun update(
        @AuthMemberId memberId: Long,
        @PathVariable reviewId: Long,
        @Valid @RequestBody request: ReviewUpdateRequest,
    ): ResponseEntity<BaseResponse<ReviewResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                reviewService.updateReview(
                    memberId = memberId,
                    reviewId = reviewId,
                    rating = request.rating!!,
                    content = request.content,
                    imagePaths = request.imagePaths,
                ),
            ),
        )

    @DeleteMapping("/reviews/{reviewId}")
    override fun remove(
        @AuthMemberId memberId: Long,
        @PathVariable reviewId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        reviewService.deleteReview(memberId, reviewId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
