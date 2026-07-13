package com.kbap.app.api.food

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberIdOrNull
import com.kbap.app.api.common.CursorParser
import com.kbap.app.api.common.Page
import com.kbap.app.api.common.SearchKeywordParser
import com.kbap.domain.food.dto.SearchFoodsInput
import com.kbap.domain.food.FoodService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodSearchController(
    private val foodService: FoodService,
) : FoodSearchApi {
    override fun search(
        keyword: String?,
        cursor: String?,
        lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = foodService.search(
            SearchFoodsInput(keyword = SearchKeywordParser.parse(keyword), cursor = CursorParser.parse(cursor), lang = lang, memberId = memberId),
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
