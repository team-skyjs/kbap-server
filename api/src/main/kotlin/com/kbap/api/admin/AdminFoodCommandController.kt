package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodCommandController(
    private val adminFoodCommandService: AdminFoodCommandService,
) : AdminFoodCommandApi {
    @PutMapping("/{id}")
    override fun updateFood(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodUpdateRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodCommandService.updateFood(adminId, id, request)))

    @PostMapping("/{id}/approve")
    override fun approve(
        @PathVariable id: Long,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodCommandService.approve(adminId, id)))

    @PostMapping("/{id}/reject")
    override fun reject(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodRejectRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodCommandService.reject(adminId, id, request.reason!!.trim())))

    @DeleteMapping("/{id}")
    override fun deleteFood(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodCommandService.deleteFood(adminId, id)))

    @PostMapping("/{id}/restore")
    override fun restoreFood(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodCommandService.restoreFood(adminId, id)))

    @PostMapping("/{id}/transitions")
    override fun transition(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodTransitionRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodCommandService.transition(adminId, id, request.transition!!, request.reason)))
}
