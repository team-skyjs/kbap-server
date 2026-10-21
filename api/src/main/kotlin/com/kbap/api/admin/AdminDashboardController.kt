package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/dashboard", version = "1.0+")
class AdminDashboardController(
    private val adminDashboardMetricsService: AdminDashboardMetricsService,
    private val adminLlmReportService: AdminLlmReportService,
    private val adminMemberQueryService: AdminMemberQueryService,
) : AdminDashboardApi {
    @GetMapping("/metrics")
    override fun getMetricsSummary(): ResponseEntity<BaseResponse<AdminDashboardMetricsResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminDashboardMetricsService.getMetricsSummary()))

    @GetMapping("/llm-costs")
    override fun getLlmCostReport(
        @RequestParam(defaultValue = "7") days: Int,
    ): ResponseEntity<BaseResponse<AdminLlmCostReportResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminLlmReportService.getLlmCostReport(days)))

    @GetMapping("/signups")
    override fun getDailySignups(
        @RequestParam(defaultValue = "30") days: Int,
        @RequestParam(required = false) isBot: Boolean?,
    ): ResponseEntity<BaseResponse<List<AdminDailyCountResponse>>> =
        ResponseEntity.ok(BaseResponse.ok(adminMemberQueryService.getDailySignups(days, isBot)))
}
