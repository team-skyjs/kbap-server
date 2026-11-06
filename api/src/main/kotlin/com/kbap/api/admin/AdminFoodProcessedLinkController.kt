package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.api.food.FoodProcessedLinkService
import com.kbap.api.food.LinkedProcessedFoodResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods/{id}/processed-link", version = "1.0+")
class AdminFoodProcessedLinkController(
    private val linkService: FoodProcessedLinkService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminFoodProcessedLinkApi {
    @PutMapping
    override fun link(
        @PathVariable id: Long,
        @RequestHeader("X-Target-Environment") target: String,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
        @Valid @RequestBody request: AdminFoodProcessedLinkRequest,
    ): ResponseEntity<BaseResponse<AdminFoodProcessedLinkResponse>> {
        verifyEnvironment(target)
        val linked = linkService.link(id, request.processedFoodId!!, adminAccountId)
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodProcessedLinkResponse(id, linked)))
    }

    @DeleteMapping
    override fun unlink(
        @PathVariable id: Long,
        @RequestHeader("X-Target-Environment") target: String,
    ): ResponseEntity<BaseResponse<AdminFoodProcessedUnlinkResponse>> {
        verifyEnvironment(target)
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodProcessedUnlinkResponse(linkService.unlink(id))))
    }

    private fun verifyEnvironment(target: String) {
        if (target != environment) throw BusinessException(ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
    }
}

@Schema(description = "메뉴 음식 → 가공식품 연결 지정")
data class AdminFoodProcessedLinkRequest(
    @field:NotNull
    @field:Schema(description = "연결할 가공식품 id — 누끼 이미지가 승인된 상품만", example = "2866")
    val processedFoodId: Long? = null,
)

@Schema(description = "연결 결과")
data class AdminFoodProcessedLinkResponse(
    val foodId: Long,
    val processedFood: LinkedProcessedFoodResponse,
)

@Schema(description = "연결 해제 결과")
data class AdminFoodProcessedUnlinkResponse(
    @field:Schema(description = "연결이 있어 해제했으면 true, 원래 없었으면 false(멱등)")
    val unlinked: Boolean,
)
