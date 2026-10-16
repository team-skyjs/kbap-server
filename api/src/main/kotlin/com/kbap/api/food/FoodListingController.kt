package com.kbap.api.food

import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.SearchPage
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API, version = "3.1+")
class FoodListingController(
    private val listingService: FoodListingService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
) : FoodListingApi {
    @GetMapping("/foods")
    override fun browse(
        @Valid @ModelAttribute request: FoodListingRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<SearchPage<FoodSummaryResponse>>> =
        response(request, memberId, saved = false)

    @GetMapping("/bookmarks")
    override fun saved(
        @Valid @ModelAttribute request: FoodListingRequest,
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<SearchPage<FoodSummaryResponse>>> =
        response(request, memberId, saved = true)

    private fun response(request: FoodListingRequest, memberId: Long?, saved: Boolean): ResponseEntity<BaseResponse<SearchPage<FoodSummaryResponse>>> {
        val page = listingService.getFoodListingPage(
            lang = LanguageCode.from(request.lang),
            sort = FoodListingSort.parse(request.sort),
            cursor = FoodListingCursor.parse(request.cursor),
            memberId = memberId,
            risks = RiskFilterParser.parse(request.risk),
            saved = saved,
        )
        val ids = page.items.map { it.foodId }
        val bookmarked = if (saved) ids.toSet() else bookmarkService.getBookmarkedFoodIds(memberId, ids)
        val ratings = reviewService.getFoodRatings(ids)
        return ResponseEntity.ok(BaseResponse.ok(SearchPage(
            page.items.map { FoodSummaryResponse.from(it, it.foodId in bookmarked, ratings[it.foodId]) },
            page.hasNext, page.nextCursor,
        )))
    }
}
