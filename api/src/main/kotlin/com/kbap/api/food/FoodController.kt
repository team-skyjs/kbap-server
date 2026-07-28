package com.kbap.api.food

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.util.CursorParser
import com.kbap.api.core.Page
import com.kbap.common.util.SearchKeywordParser
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.domain.LanguageCode
import com.kbap.api.bookmark.BookmarkService
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.food.dto.BrowseFoodsInput
import com.kbap.common.domain.food.dto.FoodSummaryView
import com.kbap.common.domain.food.dto.GetFoodDetailInput
import com.kbap.common.domain.food.dto.SearchFoodsInput
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodController(
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
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
        val result = foodService.searchFoodPage(
            SearchFoodsInput(
                keyword = SearchKeywordParser.parse(request.keyword),
                cursor = CursorParser.parse(request.cursor),
                lang = LanguageCode.from(request.lang),
                memberId = memberId,
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
        return ResponseEntity.ok(BaseResponse.ok(FoodDetailResponse.from(result, bookmarked)))
    }

    private fun toPage(
        items: List<FoodSummaryView>,
        hasNext: Boolean,
        nextCursor: Long?,
        memberId: Long?,
    ): Page<FoodSummaryResponse> {
        val bookmarkedIds = bookmarkService.getBookmarkedFoodIds(memberId, items.map { it.foodId })
        return Page(
            items = items.map { FoodSummaryResponse.from(it, it.foodId in bookmarkedIds) },
            hasNext = hasNext,
            nextCursor = nextCursor,
        )
    }
}
