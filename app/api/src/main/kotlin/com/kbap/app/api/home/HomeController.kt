package com.kbap.app.api.home

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberIdOrNull
import com.kbap.application.home.HomeApplicationService
import com.kbap.common.core.lang.LanguageCode
import com.kbap.domain.bookmark.BookmarkService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/home")
class HomeController(
    private val homeApplicationService: HomeApplicationService,
    private val bookmarkService: BookmarkService,
) : HomeApi {
    @GetMapping
    override fun home(
        @Valid @ModelAttribute request: HomeRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<HomeResponse>> {
        val result = homeApplicationService.getHome(memberId, LanguageCode.from(request.lang))
        val foodIds = (result.popularFoods + result.recentScans).map { it.foodId }
        val bookmarkedFoodIds = bookmarkService.getBookmarkedFoodIds(memberId, foodIds)
        return ResponseEntity.ok(
            BaseResponse.ok(HomeResponse.from(result, authenticated = memberId != null, bookmarkedFoodIds = bookmarkedFoodIds)),
        )
    }
}
