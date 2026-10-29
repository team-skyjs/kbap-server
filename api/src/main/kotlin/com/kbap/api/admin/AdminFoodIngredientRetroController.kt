package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods/ingredients/retro", version = "1.0+")
class AdminFoodIngredientRetroController(
    private val retroService: FoodIngredientRetroService,
) : AdminFoodIngredientRetroApi {
    @PostMapping
    override fun startRetro(
        @Valid @RequestBody request: FoodIngredientRetroRequest,
    ): ResponseEntity<BaseResponse<FoodIngredientRetroStartResponse>> {
        val job = retroService.startRetro(request.codes!!, request.dryRun!!, request.rejudge ?: false, request.foodIds)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.ok(FoodIngredientRetroStartResponse(job.jobId)))
    }

    @GetMapping("/{jobId}")
    override fun getRetroJob(
        @PathVariable jobId: String,
    ): ResponseEntity<BaseResponse<FoodIngredientRetroJob>> =
        ResponseEntity.ok(BaseResponse.ok(retroService.getRetroJob(jobId)))
}
