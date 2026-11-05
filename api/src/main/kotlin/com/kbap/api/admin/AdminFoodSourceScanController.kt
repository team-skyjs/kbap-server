package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN, version = "1.0+")
class AdminFoodSourceScanController(
    private val sourceScans: AdminFoodSourceScanService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminFoodSourceScanApi {
    @GetMapping("/foods/{foodId}/source-scans")
    override fun getSourceScans(
        @PathVariable foodId: Long,
        @RequestParam(defaultValue = "5") limit: Int,
    ): ResponseEntity<BaseResponse<AdminFoodSourceScans>> {
        if (limit !in 1..MAX_LIMIT) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ResponseEntity.ok(BaseResponse.ok(sourceScans.getSourceScans(foodId, limit)))
    }

    @PostMapping("/scan-result-foods/backfill")
    override fun backfill(
        @RequestHeader("X-Target-Environment") target: String,
        @RequestParam(defaultValue = "0") afterLogId: Long,
        @RequestParam(defaultValue = "200") batchSize: Int,
    ): ResponseEntity<BaseResponse<AdminSourceScanBackfillResult>> {
        if (target != environment) throw BusinessException(ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
        if (afterLogId < 0 || batchSize !in 1..MAX_BATCH) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ResponseEntity.ok(BaseResponse.ok(sourceScans.backfillByName(afterLogId, batchSize)))
    }

    private companion object {
        const val MAX_LIMIT = 20
        const val MAX_BATCH = 500
    }
}
