package com.kbap.api.review

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.Page
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.domain.LanguageCode
import com.kbap.common.util.CursorParser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API, version = "1.0+")
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
                    servingSpeed = request.servingSpeed,
                    staffKindness = request.staffKindness,
                    content = request.content,
                    imagePaths = request.imagePaths,
                    place = request.place?.toDomain(),
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
                    servingSpeed = request.servingSpeed,
                    staffKindness = request.staffKindness,
                    content = request.content,
                    imagePaths = request.imagePaths,
                    place = request.place?.toDomain(),
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

    @PostMapping("/reviews/{reviewId}/like")
    override fun like(
        @AuthMemberId memberId: Long,
        @PathVariable reviewId: Long,
        @RequestParam liked: Boolean,
    ): ResponseEntity<BaseResponse<Unit>> {
        if (liked) {
            reviewService.likeReview(memberId, reviewId)
        } else {
            reviewService.unlikeReview(memberId, reviewId)
        }
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @GetMapping("/reviews")
    override fun listReviews(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestParam(required = false) foodId: Long?,
        @Valid @ModelAttribute request: ReviewListRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                reviewService.getReviewPage(
                    memberId,
                    foodId,
                    request.countryCode,
                    LanguageCode.from(request.lang),
                    CursorParser.parse(request.cursor),
                ),
            ),
        )

    @GetMapping("/reviews/me")
    override fun listMyReviews(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute request: ReviewPageRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                reviewService.getMyReviewPage(memberId, LanguageCode.from(request.lang), CursorParser.parse(request.cursor)),
            ),
        )
}
