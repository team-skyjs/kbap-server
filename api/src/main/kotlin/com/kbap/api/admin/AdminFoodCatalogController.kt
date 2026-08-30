package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodCatalogController(
    private val adminFoodService: AdminFoodService,
) : AdminFoodCatalogApi {
    @GetMapping
    override fun getFoodPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: FoodContentStatus?,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminFoodListResponse.from(adminFoodService.getFoodPage(page.coerceAtLeast(1), q, status)),
            ),
        )
}
