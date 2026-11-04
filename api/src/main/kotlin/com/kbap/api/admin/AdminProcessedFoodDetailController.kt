package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.EntityStatus
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods", version = "1.0+")
class AdminProcessedFoodDetailController(
    private val detailService: AdminProcessedFoodDetailService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminProcessedFoodDetailApi {
    @GetMapping("/{id}")
    override fun getProduct(
        @PathVariable id: Long,
        @RequestHeader("X-Target-Environment") target: String,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodDetailResponse>> {
        if (target != environment) throw BusinessException(ErrorCode.PROCESSED_FOOD_ENVIRONMENT_MISMATCH)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(detailService.getProduct(id)))
    }
}

@Schema(description = "어드민 가공식품 단건(숨긴 상품 포함)")
data class AdminProcessedFoodDetailResponse(
    val processedFoodId: Long,
    val productName: String,
    val normalizedName: String,
    @field:Schema(description = "HACCP·C002·MANUAL", example = "HACCP")
    val source: String,
    @field:Schema(description = "ACTIVE(앱에 보임)·DELETED(숨김)")
    val status: EntityStatus,
    @field:Schema(description = "포장 전체 kcal — 없으면 null", nullable = true)
    val kcalTotal: Int?,
    @field:Schema(nullable = true)
    val capacity: String?,
    @field:Schema(description = "제조원 원문", nullable = true)
    val manufacturer: String?,
    @field:Schema(description = "대표 사진 — 승인된 누끼, 없으면 원본(HACCP http 는 https 로)", nullable = true)
    val imageUrl: String?,
    @field:Schema(description = "누끼 검수 상태 PENDING·APPROVED·REJECTED — 누끼 없으면 null", nullable = true)
    val cutoutReviewStatus: String?,
)
