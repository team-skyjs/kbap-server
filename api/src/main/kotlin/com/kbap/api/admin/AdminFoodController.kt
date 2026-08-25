package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.AdminFoodFilter
import com.kbap.common.domain.food.AdminFoodSort
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN, version = "1.0+")
class AdminFoodController(
    private val adminFoodService: AdminFoodService,
) : AdminFoodApi {
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
        return ResponseEntity.ok(BaseResponse.ok(adminFoodService.getFoodPage(filter, AdminPaging.page(page), AdminPaging.size(size))))
    }

    @GetMapping("/foods/{id}")
    override fun getFood(@PathVariable id: Long): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.getFoodDetail(id)))

    @GetMapping("/ingredients")
    override fun getIngredients(): ResponseEntity<BaseResponse<AdminIngredientCatalogResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.getIngredients()))

    @PutMapping("/foods/{id}")
    override fun updateFood(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodUpdateRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.updateFood(adminId, id, request)))

    @PostMapping("/foods/{id}/approve")
    override fun approve(
        @PathVariable id: Long,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.approve(adminId, id)))

    @PostMapping("/foods/{id}/reject")
    override fun reject(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodRejectRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.reject(adminId, id, request.reason!!.trim())))

    @DeleteMapping("/foods/{id}")
    override fun deleteFood(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.deleteFood(adminId, id)))

    @PostMapping("/foods/{id}/restore")
    override fun restoreFood(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.restoreFood(adminId, id)))

    @PostMapping("/foods/{id}/transitions")
    override fun transition(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodTransitionRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.transition(adminId, id, request.transition!!, request.reason)))

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
