package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden
@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodIngredientAuditController(
    private val auditService: AdminFoodIngredientAuditService,
    private val backfillService: AdminFoodIngredientBackfillService,
) {
    @GetMapping("/ingredient-audit")
    fun auditIngredients(): ResponseEntity<BaseResponse<AdminFoodIngredientAuditResponse>> =
        ResponseEntity.ok(BaseResponse.ok(auditService.auditIngredients()))

    @PostMapping("/ingredient-backfill")
    fun backfillIngredients(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
    ): ResponseEntity<BaseResponse<AdminFoodIngredientBackfillResponse>> =
        ResponseEntity.ok(BaseResponse.ok(backfillService.backfill(dryRun)))

    @GetMapping("/ingredient-backfill-report")
    fun getBackfillReport(): ResponseEntity<BaseResponse<AdminFoodIngredientBackfillReportResponse>> =
        ResponseEntity.ok(BaseResponse.ok(backfillService.getBackfillReport()))
}
