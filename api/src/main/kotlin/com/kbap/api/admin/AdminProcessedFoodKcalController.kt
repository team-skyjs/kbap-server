package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/{id}/kcal", version = "1.0+")
class AdminProcessedFoodKcalController(
    private val manualService: AdminProcessedFoodManualService,
) : AdminProcessedFoodKcalApi {
    @PatchMapping
    override fun updateKcal(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodKcalRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodKcalResponse>> =
        ResponseEntity.ok(BaseResponse.ok(manualService.updateKcal(id, request.kcalTotal)))
}

@Schema(description = "가공식품 칼로리 입력")
data class AdminProcessedFoodKcalRequest(
    @field:Min(value = 0, message = "kcalTotal 은 0 이상이어야 합니다")
    @field:Schema(description = "포장 전체 칼로리(kcal) — null 이면 지운다", example = "285", nullable = true)
    val kcalTotal: Int? = null,
)

@Schema(description = "가공식품 칼로리")
data class AdminProcessedFoodKcalResponse(
    val processedFoodId: Long,
    @field:Schema(nullable = true)
    val kcalTotal: Int?,
)
