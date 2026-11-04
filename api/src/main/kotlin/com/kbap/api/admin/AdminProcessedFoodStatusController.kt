package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.EntityStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/{id}/status", version = "1.0+")
class AdminProcessedFoodStatusController(
    private val statusService: AdminProcessedFoodStatusService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminProcessedFoodStatusApi {
    @PatchMapping
    override fun changeStatus(
        @PathVariable id: Long,
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminProcessedFoodStatusRequest,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodStatusResponse>> {
        if (target != environment) throw BusinessException(ErrorCode.PROCESSED_FOOD_ENVIRONMENT_MISMATCH)
        return ResponseEntity.ok(BaseResponse.ok(statusService.changeStatus(id, request.status!!, adminId)))
    }
}

@Schema(description = "가공식품 숨김·복원 요청")
data class AdminProcessedFoodStatusRequest(
    @field:NotNull(message = "status 는 필수입니다")
    @field:Schema(description = "ACTIVE(보이기)·DELETED(숨기기)", example = "DELETED", requiredMode = Schema.RequiredMode.REQUIRED)
    val status: EntityStatus? = null,
)

@Schema(description = "가공식품 숨김·복원 결과")
data class AdminProcessedFoodStatusResponse(
    val processedFoodId: Long,
    val status: EntityStatus,
    @field:Schema(description = "이번 요청으로 상태가 바뀌었으면 true, 이미 그 상태였으면 false(멱등)")
    val changed: Boolean,
)
