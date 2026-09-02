package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/dashboard/llm-costs", version = "1.0+")
class AdminLlmReportController(
    private val adminLlmReportService: AdminLlmReportService,
) : AdminLlmReportApi {
    @GetMapping
    override fun getLlmCostReport(
        @RequestParam(defaultValue = "7") days: Int,
    ): ResponseEntity<BaseResponse<AdminLlmCostReportResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminLlmReportService.getLlmCostReport(days)))
}
