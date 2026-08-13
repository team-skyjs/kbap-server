package com.kbap.api.home

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.domain.LanguageCode
import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.review.ReviewService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/home")
class HomeController(
    private val homeService: HomeService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
) : HomeApi {
    @GetMapping
    override fun home(
        @Valid @ModelAttribute request: HomeRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<HomeResponse>> {
        val result = homeService.getHome(memberId, LanguageCode.from(request.lang))
        val foodIds = (result.popularFoods + result.recentScans).map { it.foodId }
        val bookmarkedFoodIds = bookmarkService.getBookmarkedFoodIds(memberId, foodIds)
        val ratings = reviewService.getFoodRatings(foodIds)
        return ResponseEntity.ok(
            BaseResponse.ok(
                HomeResponse.from(
                    result,
                    authenticated = memberId != null,
                    bookmarkedFoodIds = bookmarkedFoodIds,
                    ratings = ratings,
                ),
            ),
        )
    }
}
