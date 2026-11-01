package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.processedfood.ProcessedFoodDetailResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/manual", version = "1.0+")
class AdminProcessedFoodManualController(
    private val manualService: AdminProcessedFoodManualService,
) : AdminProcessedFoodManualApi {
    @PostMapping
    override fun createManualProduct(@Valid @RequestBody request: AdminProcessedFoodManualRequest): ResponseEntity<BaseResponse<ProcessedFoodDetailResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(BaseResponse.ok(manualService.createManualProduct(request)))

    @PatchMapping("/{id}")
    override fun updateManualProduct(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodManualRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(manualService.updateManualProduct(id, request)))
}
