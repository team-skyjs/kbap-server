package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/dashboard", version = "1.0+")
class AdminDashboardController(
    private val adminDashboardMetricsService: AdminDashboardMetricsService,
) : AdminDashboardApi {
    @GetMapping("/metrics")
    override fun getMetricsSummary(): ResponseEntity<BaseResponse<AdminDashboardMetricsResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminDashboardMetricsService.getMetricsSummary()))
}
