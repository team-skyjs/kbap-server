package com.kbap.app.api.admin

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.application.foodimage.FoodImageBatchSubmitService
import com.kbap.domain.food.FoodService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods")
class AdminController(
    private val foodService: FoodService,
    private val foodImageBatchSubmitService: FoodImageBatchSubmitService,
) : AdminApi {
    @PostMapping
    override fun seed(
        @Valid @RequestBody request: AdminFoodSeedRequest,
    ): ResponseEntity<BaseResponse<AdminFoodSeedResponse>> {
        val result = foodService.seedIncomplete(request.koreanNames.orEmpty().toSet())
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodSeedResponse.from(result)))
    }

    @PostMapping("/images")
    override fun submitFoodImages(): ResponseEntity<BaseResponse<AdminFoodImageSubmitResponse>> {
        val result = foodImageBatchSubmitService.submitMissingImages()
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodImageSubmitResponse.from(result)))
    }
}
