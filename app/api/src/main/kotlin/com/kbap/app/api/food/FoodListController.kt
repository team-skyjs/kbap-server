package com.kbap.app.api.food

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberIdOrNull
import com.kbap.app.api.common.Page
import com.kbap.application.food.dto.BrowseFoodsInput
import com.kbap.application.food.FoodService
import com.kbap.application.food.usecase.resolveCursor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodListController(
    private val foodService: FoodService,
) : FoodListApi {
    override fun browse(
        cursor: String?,
        lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>> {
        val result = foodService.browse(
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
