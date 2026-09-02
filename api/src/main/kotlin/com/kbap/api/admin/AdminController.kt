package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.food.FoodImageBatchSubmitService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminController(
    private val adminFoodService: AdminFoodService,
    private val foodImageBatchSubmitService: FoodImageBatchSubmitService,
    private val adminImageBatchQueryService: AdminImageBatchQueryService,
) : AdminApi {
    @PostMapping
    override fun seed(
        @Valid @RequestBody request: AdminFoodSeedRequest,
    ): ResponseEntity<BaseResponse<AdminFoodSeedResponse>> {
        val result = adminFoodService.seedIncomplete(request.koreanNames.orEmpty().toSet())
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodSeedResponse.from(result)))
    }

    @GetMapping("/images")
    override fun getImageBatches(): ResponseEntity<BaseResponse<AdminImageBatchListResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminImageBatchListResponse.from(adminImageBatchQueryService.getRecentBatches())),
        )

    @PostMapping("/images")
    override fun submitFoodImages(): ResponseEntity<BaseResponse<AdminFoodImageSubmitResponse>> {
        val result = foodImageBatchSubmitService.submitMissingImages()
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodImageSubmitResponse.from(result)))
    }
}
