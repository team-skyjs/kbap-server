package com.kbap.app.api.bookmark

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.CursorParser
import com.kbap.app.api.common.Page
import com.kbap.app.api.common.auth.AuthMemberId
import com.kbap.app.api.food.FoodSummaryResponse
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.bookmark.BookmarkService
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
@RequestMapping(ApiPaths.V1 + "/bookmarks")
class BookmarkController(
    private val bookmarkService: BookmarkService,
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
        return ResponseEntity.ok(
            BaseResponse.ok(
                Page(
                    items = result.items.map { FoodSummaryResponse.from(it, bookmarked = true) },
                    hasNext = result.hasNext,
                    nextCursor = result.nextCursor,
                ),
            ),
        )
    }
}
