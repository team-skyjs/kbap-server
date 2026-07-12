package com.meogo.app.api.food

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.Page
import com.meogo.application.client.food.dto.BrowseFoodsInput
import com.meogo.application.client.food.usecase.BrowseFoodsUseCase
import com.meogo.application.client.food.usecase.resolveCursor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodListController(
    private val browseFoodsUseCase: BrowseFoodsUseCase,
) : FoodListApi {
    override fun browse(
        cursor: String?,
        lang: String?,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = browseFoodsUseCase.browse(
            BrowseFoodsInput(cursor = resolveCursor(cursor), lang = lang, memberId = memberId),
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
