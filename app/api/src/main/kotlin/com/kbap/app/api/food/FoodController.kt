package com.kbap.app.api.food

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.CursorParser
import com.kbap.app.api.common.Page
import com.kbap.app.api.common.SearchKeywordParser
import com.kbap.app.api.common.auth.AuthMemberIdOrNull
import com.kbap.domain.bookmark.BookmarkService
import com.kbap.domain.food.FoodService
import com.kbap.domain.food.dto.BrowseFoodsInput
import com.kbap.domain.food.dto.FoodSummaryView
import com.kbap.domain.food.dto.GetFoodDetailInput
import com.kbap.domain.food.dto.SearchFoodsInput
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodController(
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
) : FoodApi {
    @GetMapping
    override fun browse(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = foodService.browse(
            BrowseFoodsInput(cursor = CursorParser.parse(cursor), lang = lang, memberId = memberId),
        )
        return ResponseEntity.ok(BaseResponse.ok(toPage(result.items, result.hasNext, result.nextCursor, memberId)))
    }

    @GetMapping("/search")
    override fun search(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = foodService.search(
            SearchFoodsInput(keyword = SearchKeywordParser.parse(keyword), cursor = CursorParser.parse(cursor), lang = lang, memberId = memberId),
        )
        return ResponseEntity.ok(BaseResponse.ok(toPage(result.items, result.hasNext, result.nextCursor, memberId)))
    }

    @GetMapping("/{foodId}")
    override fun detail(
        @PathVariable foodId: Long,
        @RequestParam(required = false) lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>> {
        val result = foodService.getDetail(GetFoodDetailInput(foodId = foodId, lang = lang, memberId = memberId))
        val bookmarked = foodId in bookmarkService.findBookmarkedFoodIds(memberId, listOf(foodId))
        return ResponseEntity.ok(BaseResponse.ok(FoodDetailResponse.from(result, bookmarked)))
    }

    private fun toPage(
        items: List<FoodSummaryView>,
        hasNext: Boolean,
        nextCursor: Long?,
        memberId: Long?,
    ): Page<FoodSummaryResponse> {
        val bookmarkedIds = bookmarkService.findBookmarkedFoodIds(memberId, items.map { it.foodId })
        return Page(
            items = items.map { FoodSummaryResponse.from(it, it.foodId in bookmarkedIds) },
            hasNext = hasNext,
            nextCursor = nextCursor,
        )
    }
}
