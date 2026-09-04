package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods/vector-outboxes", version = "1.0+")
class AdminVectorOutboxController(
    private val adminFoodDashboardService: AdminFoodDashboardService,
) : AdminVectorOutboxApi {
    @GetMapping
    override fun getVectorOutboxPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) status: FoodVectorOutboxStatus?,
        @RequestParam(required = false) q: String?,
    ): ResponseEntity<BaseResponse<AdminVectorOutboxPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminFoodDashboardService.getVectorOutboxPage(page.coerceAtLeast(1), status, q)),
        )

    @PostMapping("/enqueue")
    override fun enqueueVectorOutboxes(): ResponseEntity<BaseResponse<AdminVectorOutboxEnqueueResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminVectorOutboxEnqueueResponse(adminFoodDashboardService.enqueueReadyFoodsForVectorSync()),
            ),
        )

    @PostMapping("/{id}/retry")
    override fun retryVectorOutbox(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminVectorOutboxRetryResponse>> {
        val payload = when (adminFoodDashboardService.retryVectorOutbox(id)) {
            AdminVectorOutboxRetryResult.RETRIED ->
                AdminVectorOutboxRetryResponse(retried = true, outboxStatus = FoodVectorOutboxStatus.PENDING)
            AdminVectorOutboxRetryResult.ALREADY_PENDING ->
                AdminVectorOutboxRetryResponse(retried = false, outboxStatus = FoodVectorOutboxStatus.PENDING)
            AdminVectorOutboxRetryResult.ALREADY_COMPLETE ->
                AdminVectorOutboxRetryResponse(retried = false, outboxStatus = FoodVectorOutboxStatus.COMPLETE)
            AdminVectorOutboxRetryResult.NOT_FOUND ->
                throw BusinessException(ErrorCode.VECTOR_OUTBOX_NOT_FOUND)
        }
        return ResponseEntity.ok(BaseResponse.ok(payload))
    }
}
