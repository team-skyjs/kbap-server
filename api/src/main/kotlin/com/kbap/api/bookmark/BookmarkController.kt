package com.kbap.api.bookmark

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.util.CursorParser
import com.kbap.api.core.Page
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.food.FoodSummaryResponse
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/bookmarks")
class BookmarkController(
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
) : BookmarkApi {
    @PostMapping
    override fun register(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: BookmarkCreateRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        bookmarkService.bookmark(memberId, request.foodId!!)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @PatchMapping("/{foodId}")
    override fun unregister(
        @AuthMemberId memberId: Long,
        @PathVariable foodId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        bookmarkService.unbookmark(memberId, foodId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @GetMapping
    override fun list(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute request: BookmarkListRequest,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = bookmarkService.getBookmarkPage(memberId, LanguageCode.from(request.lang), CursorParser.parse(request.cursor))
        val ratings = reviewService.getFoodRatings(result.items.map { it.foodId })
        return ResponseEntity.ok(
            BaseResponse.ok(
                Page(
                    items = result.items.map { FoodSummaryResponse.from(it, bookmarked = true, ratings[it.foodId]) },
                    hasNext = result.hasNext,
                    nextCursor = result.nextCursor,
                ),
            ),
        )
    }
}
