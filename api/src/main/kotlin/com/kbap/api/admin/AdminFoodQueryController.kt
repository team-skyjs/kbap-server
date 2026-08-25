package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.AdminFoodFilter
import com.kbap.common.domain.food.AdminFoodSort
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN, version = "1.0+")
class AdminFoodQueryController(
    private val adminFoodQueryService: AdminFoodQueryService,
) : AdminFoodQueryApi {
    @GetMapping("/foods")
    override fun getFoods(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) ingredient: String?,
        @RequestParam(required = false) translation: String?,
        @RequestParam(required = false) status: FoodContentStatus?,
        @RequestParam(required = false) failureKind: FoodContentFailureKind?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(defaultValue = "id,desc") sort: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>> {
        val (sortField, descending) = parseSort(sort)
        val filter = AdminFoodFilter(
            q = q,
            ingredient = ingredient,
            translation = translation,
            status = status,
            failureKind = failureKind,
            includeDeleted = includeDeleted,
            sort = sortField,
            descending = descending,
        )
        return ResponseEntity.ok(BaseResponse.ok(adminFoodQueryService.getFoodPage(filter, AdminPaging.page(page), AdminPaging.size(size))))
    }

    @GetMapping("/foods/{id}")
    override fun getFood(@PathVariable id: Long): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodQueryService.getFoodDetail(id)))

    @GetMapping("/ingredients")
    override fun getIngredients(): ResponseEntity<BaseResponse<AdminIngredientCatalogResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodQueryService.getIngredients()))

    private fun parseSort(sort: String): Pair<AdminFoodSort, Boolean> {
        val parts = sort.split(",")
        val field = when (parts[0].trim()) {
            "id" -> AdminFoodSort.ID
            "updatedAt" -> AdminFoodSort.UPDATED_AT
            "displayName" -> AdminFoodSort.DISPLAY_NAME
            else -> throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        val descending = when (parts.getOrNull(1)?.trim()?.lowercase() ?: "desc") {
            "desc" -> true
            "asc" -> false
            else -> throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        return field to descending
    }
}
