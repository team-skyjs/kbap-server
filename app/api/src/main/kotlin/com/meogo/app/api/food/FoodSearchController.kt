package com.meogo.app.api.food

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberIdOrNull
import com.meogo.app.api.common.Page
import com.meogo.application.food.dto.SearchFoodsInput
import com.meogo.application.food.usecase.SearchFoodsUseCase
import com.meogo.application.food.usecase.resolveCursor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodSearchController(
    private val searchFoodsUseCase: SearchFoodsUseCase,
) : FoodSearchApi {
    override fun search(
        keyword: String?,
        cursor: String?,
        lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = searchFoodsUseCase.search(
            SearchFoodsInput(keyword = keyword, cursor = resolveCursor(cursor), lang = lang, memberId = memberId),
        )
        return ResponseEntity.ok(
            BaseResponse.ok(
                Page(
                    items = result.items.map(FoodSummaryResponse::from),
                    hasNext = result.hasNext,
                    nextCursor = result.nextCursor,
                ),
            ),
        )
    }
}
