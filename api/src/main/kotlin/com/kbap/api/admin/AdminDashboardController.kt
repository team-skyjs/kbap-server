package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.FoodContentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

data class AdminDashboardResponse(
    val foods: FoodsSection,
    val contentOutbox: ContentOutboxSection,
    val vectorOutbox: VectorOutboxSection,
    val metrics: MetricsSection,
) {
    data class FoodsSection(val total: Long, val byStatus: List<StatusCount>, val readyRatio: Double)
    data class StatusCount(val code: FoodContentStatus, val label: String, val count: Long)
    data class ContentOutboxSection(val pending: Long, val sent: Long, val complete: Long, val canceled: Long, val stuckCount: Long, val stuckHours: Int)
    data class VectorOutboxSection(val pending: Long, val complete: Long, val failed: Long, val unenqueued: Long)
    data class MetricsSection(
        val days: Int,
        val totalActiveMembers: Long,
        val dailyScans: List<DailyCount>,
        val dailyNewFoods: List<DailyCount>,
        val llmCost: LlmCostSection,
    )
    data class DailyCount(val date: LocalDate, val count: Long)
    data class LlmCostSection(val scopeNote: String, val daily: List<LlmCostDay>)
    data class LlmCostDay(val date: LocalDate, val callCount: Long, val costUsd: BigDecimal, val costKrw: BigDecimal, val models: List<LlmCostModel>)
    data class LlmCostModel(val modelName: String, val callCount: Long, val inputTokens: Long, val outputTokens: Long, val costUsd: BigDecimal, val costKrw: BigDecimal)
}

@Service
class AdminDashboardService(
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminFoodOutboxQueryService: AdminFoodOutboxQueryService,
    private val adminDashboardMetricsService: AdminDashboardMetricsService,
) {
    @Transactional(readOnly = true)
    fun getDashboard(days: Int): AdminDashboardResponse {
        val foods = adminFoodDashboardService.getDashboard()
        val outbox = adminFoodOutboxQueryService.getOutboxDashboard()
        val vector = adminFoodDashboardService.getVectorOutboxDashboard()
        val metrics = adminDashboardMetricsService.getMetrics(days)
        return AdminDashboardResponse(
            foods = AdminDashboardResponse.FoodsSection(
                total = foods.total,
                byStatus = listOf(
                    statusCount(FoodContentStatus.FAILED, foods.failed),
                    statusCount(FoodContentStatus.PENDING_IMAGE, foods.pendingImage),
                    statusCount(FoodContentStatus.PENDING_REVIEW, foods.pendingReview),
                    statusCount(FoodContentStatus.READY, foods.ready),
                ),
                readyRatio = foods.readyRatio,
            ),
            contentOutbox = AdminDashboardResponse.ContentOutboxSection(
                outbox.pending, outbox.sent, outbox.complete, outbox.canceled, outbox.stuckCount, outbox.stuckHours,
            ),
            vectorOutbox = AdminDashboardResponse.VectorOutboxSection(vector.pending, vector.complete, vector.failed, vector.unenqueued),
            metrics = AdminDashboardResponse.MetricsSection(
                days = metrics.days,
                totalActiveMembers = metrics.totalActiveMembers,
                dailyScans = metrics.weeklyScans.map { AdminDashboardResponse.DailyCount(it.date, it.count) },
                dailyNewFoods = metrics.weeklyNewFoods.map { AdminDashboardResponse.DailyCount(it.date, it.count) },
                llmCost = AdminDashboardResponse.LlmCostSection(
                    scopeNote = AdminDashboardMetricsService.LLM_COST_SCOPE_NOTE,
                    daily = metrics.llmCostDaily.map { day ->
                        AdminDashboardResponse.LlmCostDay(
                            day.date, day.callCount, day.costUsd, day.costKrw,
                            day.models.map { AdminDashboardResponse.LlmCostModel(it.modelName, it.callCount, it.inputTokens, it.outputTokens, it.costUsd, it.costKrw) },
                        )
                    },
                ),
            ),
        )
    }

    private fun statusCount(status: FoodContentStatus, count: Long) =
        AdminDashboardResponse.StatusCount(status, status.displayName, count)
}

@Tag(name = "관리자 대시보드", description = "음식 상태 집계·콘텐츠/벡터 아웃박스·기간 지표·LLM 비용")
@SecurityRequirement(name = "bearerAuth")
interface AdminDashboardApi {
    @Operation(
        summary = "대시보드",
        description = "`days`(1..90, 기본 7) 기간의 스캔·신규 음식·LLM 비용 지표. 상태는 `{code,label}` 쌍, 비용은 USD·KRW 병기와 집계 범위(`scopeNote`), 콘텐츠 아웃박스에 고착(`stuckCount`, 발행 후 `stuckHours` 경과) 포함.",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "400", description = "days 범위 밖(COMMON-002)"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getDashboard(@Parameter(description = "집계 일수(1..90)", example = "7") days: Int): ResponseEntity<BaseResponse<AdminDashboardResponse>>
}

@RestController
@RequestMapping(ApiPaths.ADMIN + "/dashboard", version = "1.0+")
class AdminDashboardController(
    private val adminDashboardService: AdminDashboardService,
) : AdminDashboardApi {
    @GetMapping
    override fun getDashboard(
        @RequestParam(defaultValue = "7") days: Int,
    ): ResponseEntity<BaseResponse<AdminDashboardResponse>> {
        if (days !in AdminDashboardMetricsService.DAYS_RANGE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ResponseEntity.ok(BaseResponse.ok(adminDashboardService.getDashboard(days)))
    }
}
