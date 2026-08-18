package com.kbap.api.food

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.util.CursorParser
import com.kbap.api.core.Page
import com.kbap.common.util.SearchKeywordParser
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.review.ReviewService
import com.kbap.api.member.MemberService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/foods")
class FoodController(
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
    private val memberService: MemberService,
) : FoodApi {
    @GetMapping
    override fun browse(
        @Valid @ModelAttribute request: FoodBrowseRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = foodService.getFoodPage(
            BrowseFoodsInput(cursor = CursorParser.parse(request.cursor), lang = LanguageCode.from(request.lang), memberId = memberId),
        )
        return ResponseEntity.ok(BaseResponse.ok(toPage(result.items, result.hasNext, result.nextCursor, memberId)))
    }

    @GetMapping("/search")
    override fun search(
        @Valid @ModelAttribute request: FoodSearchRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val scope = FoodSearchScope.from(request.scope)
        if (scope == FoodSearchScope.SCANNED && memberId == null) {
            throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        }
        val keyword = when (scope) {
            FoodSearchScope.SCANNED -> request.keyword?.let { SearchKeywordParser.parse(it) }
            FoodSearchScope.ALL -> SearchKeywordParser.parse(request.keyword)
        }
        val result = foodService.searchFoodPage(
            SearchFoodsInput(
                keyword = keyword,
                cursor = CursorParser.parse(request.cursor),
                lang = LanguageCode.from(request.lang),
                memberId = memberId,
                scope = scope,
            ),
        )
        return ResponseEntity.ok(BaseResponse.ok(toPage(result.items, result.hasNext, result.nextCursor, memberId)))
    }

    @GetMapping("/{foodId}")
    override fun detail(
        @PathVariable foodId: Long,
        @Valid @ModelAttribute request: FoodDetailRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>> {
        val result = foodService.getDetail(
            GetFoodDetailInput(foodId = foodId, lang = LanguageCode.from(request.lang), memberId = memberId),
        )
        val bookmarked = foodId in bookmarkService.getBookmarkedFoodIds(memberId, listOf(foodId))
        val recentReviews = reviewService.getRecentFoodReviews(foodId, memberId, LanguageCode.from(request.lang))
        return ResponseEntity.ok(
            BaseResponse.ok(FoodDetailResponse.from(result, bookmarked, reviewSummaryOf(foodId, memberId), recentReviews)),
        )
    }

    private fun reviewSummaryOf(foodId: Long, memberId: Long?): FoodDetailResponse.ReviewSummaryResponse {
        val viewer = memberId?.let { memberService.getMemberOrNull(it) }
        val rating = reviewService.getFoodRatingSummary(foodId, viewer?.profile?.countryCode?.name)
        return FoodDetailResponse.ReviewSummaryResponse.from(rating, sameCountryVisible = viewer != null)
    }

    private fun toPage(
        items: List<FoodSummaryView>,
        hasNext: Boolean,
        nextCursor: Long?,
        memberId: Long?,
    ): Page<FoodSummaryResponse> {
        val foodIds = items.map { it.foodId }
        val bookmarkedIds = bookmarkService.getBookmarkedFoodIds(memberId, foodIds)
        val ratings = reviewService.getFoodRatings(foodIds)
        return Page(
            items = items.map { FoodSummaryResponse.from(it, it.foodId in bookmarkedIds, ratings[it.foodId]) },
            hasNext = hasNext,
            nextCursor = nextCursor,
        )
    }
}
