package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden
@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodIngredientAuditController(
    private val auditService: AdminFoodIngredientAuditService,
) {
    @GetMapping("/ingredient-audit")
    fun auditIngredients(): ResponseEntity<BaseResponse<AdminFoodIngredientAuditResponse>> =
        ResponseEntity.ok(BaseResponse.ok(auditService.auditIngredients()))
}
