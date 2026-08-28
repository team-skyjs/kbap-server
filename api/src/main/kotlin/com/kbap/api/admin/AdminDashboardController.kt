package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class AdminDashboardResponse(
    val foods: FoodsSection,
    val contentOutbox: ContentOutboxSection,
    val vectorOutbox: VectorOutboxSection,
    val metrics: MetricsSection,
    val pendingReviewPreview: List<FoodPreview>,
    val pendingImagePreview: List<FoodPreview>,
    val generatingPreview: List<GeneratingPreview>,
) {
    data class FoodsSection(val total: Long, val byStatus: List<StatusCount>, val readyRatio: Double)
    data class StatusCount(val code: FoodContentStatus, val label: String, val count: Long)
    data class ContentOutboxSection(
        val pending: Long,
        val sent: Long,
        val complete: Long,
        val canceled: Long,
        val stuckCount: Long,
        val stuckHours: Int,
        val stuck: List<StuckOutbox>,
    )
    data class StuckOutbox(val outboxId: Long, val foodId: Long, val displayName: String, val attempts: Int, val sentAt: LocalDateTime?)
    data class VectorOutboxSection(val pending: Long, val complete: Long, val failed: Long, val unenqueued: Long, val failures: List<VectorFailure>)
    data class VectorFailure(val outboxId: Long, val foodId: Long, val displayName: String, val attempts: Int, val lastError: String?, val updatedAt: LocalDateTime)
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
    data class FoodPreview(val id: Long, val koreanName: String, val imageUrl: String?, val updatedAt: LocalDateTime)
    data class GeneratingPreview(val outboxId: Long, val foodId: Long, val displayName: String, val status: FoodContentOutboxStatus, val attempts: Int)
}

@Service
class AdminDashboardService(
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminFoodOutboxQueryService: AdminFoodOutboxQueryService,
    private val adminDashboardMetricsService: AdminDashboardMetricsService,
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodContentOutboxJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
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
                pending = outbox.pending,
                sent = outbox.sent,
                complete = outbox.complete,
                canceled = outbox.canceled,
                stuckCount = outbox.stuckCount,
                stuckHours = outbox.stuckHours,
                stuck = outbox.stuck.map { AdminDashboardResponse.StuckOutbox(it.outboxId, it.foodId, it.displayName, it.attempts, it.sentAt) },
            ),
            vectorOutbox = AdminDashboardResponse.VectorOutboxSection(
                pending = vector.pending,
                complete = vector.complete,
                failed = vector.failed,
                unenqueued = vector.unenqueued,
                failures = vector.failures.map {
                    AdminDashboardResponse.VectorFailure(it.outboxId, it.foodId, it.displayName, it.attempts, it.lastError?.take(LAST_ERROR_MAX_LENGTH), it.updatedAt)
                },
            ),
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
            pendingReviewPreview = preview(FoodContentStatus.PENDING_REVIEW),
            pendingImagePreview = preview(FoodContentStatus.PENDING_IMAGE),
            generatingPreview = outboxRepository
                .findTop14ByOutboxStatusInOrderByIdDesc(listOf(FoodContentOutboxStatus.PENDING, FoodContentOutboxStatus.SENT))
                .map { AdminDashboardResponse.GeneratingPreview(it.id, it.foodId, it.displayName, it.outboxStatus, it.attempts) },
        )
    }

    private fun preview(status: FoodContentStatus): List<AdminDashboardResponse.FoodPreview> =
        foodRepository.findByContentStatus(status, PageRequest.of(0, PREVIEW_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt", "id")))
            .content.map { toPreview(it) }

    private fun toPreview(food: Food) =
        AdminDashboardResponse.FoodPreview(food.id, food.displayName, ImageUrls.resolve(imagePublicBaseUrl, food.imageRef), food.updatedAt)

    private fun statusCount(status: FoodContentStatus, count: Long) =
        AdminDashboardResponse.StatusCount(status, status.displayName, count)

    companion object {
        const val PREVIEW_SIZE = 14
        const val LAST_ERROR_MAX_LENGTH = 200
    }
}

@Tag(name = "관리자 대시보드", description = "음식 상태 집계·콘텐츠/벡터 아웃박스·기간 지표·LLM 비용·대기 목록 미리보기")
@SecurityRequirement(name = "bearerAuth")
interface AdminDashboardApi {
    @Operation(
        summary = "대시보드",
        description = """
            `days`(1..90, 기본 7) 기간의 스캔·신규 음식·LLM 비용 지표. 상태는 `{code,label}` 쌍, 비용은 USD·KRW 병기와 집계 범위(`scopeNote`).
            콘텐츠 아웃박스는 고착 건수(`stuckCount`, 발행 후 `stuckHours` 경과)와 고착 목록 `stuck[]`(최대 20, 오래된 순), 벡터 아웃박스는 `failures[]`(최근 20, `lastError` 200자 요약).
            캐러셀용 `pendingReviewPreview`·`pendingImagePreview`(각 최근 수정순 14건)·`generatingPreview`(콘텐츠 수집 PENDING·SENT 최근 14건).
        """,
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
