package com.kbap.app.api.admin

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.domain.food.FoodService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods")
class AdminFoodController(
    private val foodService: FoodService,
) : AdminFoodApi {
    @PostMapping
    override fun seed(
        @Valid @RequestBody request: AdminFoodSeedRequest,
    ): ResponseEntity<BaseResponse<AdminFoodSeedResponse>> {
        val result = foodService.seedIncomplete(request.toKoreanNames())
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodSeedResponse.from(result)))
    }
}
