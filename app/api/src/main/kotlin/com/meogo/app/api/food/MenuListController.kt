package com.meogo.app.api.food

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.Page
import com.meogo.application.client.food.dto.BrowseMenusInput
import com.meogo.application.client.food.usecase.BrowseMenusUseCase
import com.meogo.application.client.food.usecase.resolveCursor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class MenuListController(
    private val browseMenusUseCase: BrowseMenusUseCase,
) : MenuListApi {
    override fun browse(
        cursor: String?,
        lang: String?,
    ): ResponseEntity<BaseResponse<Page<MenuSummaryResponse>>> {
        val result = browseMenusUseCase.browse(
            BrowseMenusInput(cursor = resolveCursor(cursor), lang = lang),
        )
        return ResponseEntity.ok(
            BaseResponse.ok(
                Page(
                    items = result.items.map(MenuSummaryResponse::from),
                    hasNext = result.hasNext,
                    nextCursor = result.nextCursor,
                ),
            ),
        )
    }
}
