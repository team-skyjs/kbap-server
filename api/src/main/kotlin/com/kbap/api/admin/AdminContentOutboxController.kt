package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods/content-outboxes", version = "1.0+")
class AdminContentOutboxController(
    private val adminFoodOutboxQueryService: AdminFoodOutboxQueryService,
) : AdminContentOutboxApi {
    @GetMapping
    override fun getContentOutboxPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) status: FoodContentOutboxStatus?,
        @RequestParam(required = false) q: String?,
    ): ResponseEntity<BaseResponse<AdminContentOutboxPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminFoodOutboxQueryService.getContentOutboxPage(page.coerceAtLeast(1), status, q)),
        )
}
