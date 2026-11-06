package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.food.FoodService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.scan.MenuNameEstimateAdminRow
import com.kbap.common.domain.scan.model.MenuNameEstimate
import com.kbap.common.domain.scan.MenuNameEstimateJpaRepository
import com.kbap.common.port.llm.MenuNameKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminMenuNameEstimateService(
    private val estimates: MenuNameEstimateJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val contentOutboxRepository: FoodContentOutboxJpaRepository,
    private val foodService: FoodService,
    private val adminFoodService: AdminFoodService,
) {
    private val mapper = jacksonObjectMapper()

    @Transactional(readOnly = true)
    fun getEstimatePage(filter: AdminMenuNameEstimateFilter, page: Int, size: Int): AdminMenuNameEstimatePage {
        val kind = filter.kind?.name
        val foodStatus = filter.foodStatus?.name
        val rows = estimates.findAdminRows(kind, filter.promoted, foodStatus, filter.sort.name, size, page.toLong() * size)
        val total = estimates.countAdminRows(kind, filter.promoted, foodStatus)
        val foodIds = rows.mapNotNull { it.foodId }
        val requested = foodIds.takeIf { it.isNotEmpty() }?.let { contentOutboxRepository.findInFlightRequests(it).map { o -> o.foodId }.toSet() }.orEmpty()
        val missingKeys = rows.filter { it.foodId == null }.map { it.nameKey }
        val deletedIds = missingKeys.takeIf { it.isNotEmpty() }
            ?.let { foodRepository.findLatestDeletedIdsByOriginalKoreanNames(it).associate { d -> d.originalKoreanName to d.foodId } }.orEmpty()
        return AdminMenuNameEstimatePage(
            items = rows.map { it.toItem(requested, deletedIds) },
            page = page,
            size = size,
            totalElements = total,
            hasNext = (page.toLong() + 1) * size < total,
        )
    }

    @Transactional
    fun promote(id: Long, adminId: Long): AdminMenuNameEstimatePromoteResult {
        val estimate = estimates.findByIdForUpdate(id) ?: throw BusinessException(ErrorCode.MENU_NAME_ESTIMATE_NOT_FOUND)
        if (estimate.isPromoted()) throw BusinessException(ErrorCode.MENU_NAME_ESTIMATE_ALREADY_PROMOTED)
        val existing = foodRepository.findIdByKoreanNameForUpdate(estimate.nameKey)?.let { foodRepository.findByIdForUpdate(it) }
        val (food, outcome) = if (existing == null) createFood(estimate.nameKey) to AdminMenuNameEstimatePromoteOutcome.CREATED_FOOD else existing to recollect(existing)
        estimate.promote(adminId)
        return AdminMenuNameEstimatePromoteResult(estimateId = estimate.id, foodId = food.id, outcome = outcome, promotedAt = estimate.promotedAt!!)
    }

    private fun createFood(nameKey: String): Food =
        foodService.createIncomplete(mapOf(nameKey to nameKey))[nameKey] ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)

    private fun recollect(food: Food): AdminMenuNameEstimatePromoteOutcome {
        if (food.isReady()) throw BusinessException(ErrorCode.FOOD_ALREADY_READY)
        return when (adminFoodService.recollectLockedFood(food, rejectionReason = null)) {
            AdminFoodService.RecollectOutcome.CREATED -> AdminMenuNameEstimatePromoteOutcome.CONTENT_REQUESTED
            AdminFoodService.RecollectOutcome.ALREADY_PENDING -> AdminMenuNameEstimatePromoteOutcome.ALREADY_PENDING
            AdminFoodService.RecollectOutcome.REGENERATING -> throw BusinessException(ErrorCode.FOOD_CONTENT_AND_IMAGE_JOBS_CONFLICT)
            AdminFoodService.RecollectOutcome.NOT_FOUND -> throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        }
    }

    private fun MenuNameEstimateAdminRow.toItem(requested: Set<Long>, deletedIds: Map<String, Long>): AdminMenuNameEstimateItem {
        val contentStatus = foodContentStatus?.let(FoodContentStatus::valueOf)
        return AdminMenuNameEstimateItem(
            id = id,
            nameKey = nameKey,
            kind = kind,
            englishName = englishName,
            description = description,
            avoidanceCodes = MenuNameEstimate.effectiveAvoidanceCodes(kind, nameKey, avoidanceCodes?.let { mapper.readValue<List<String>>(it) }),
            spiciness = spiciness,
            confidence = confidence?.toDouble(),
            model = model,
            promptVersion = promptVersion,
            createdAt = createdAt,
            scanCount = scanCount,
            promotedAt = promotedAt,
            promotedBy = promotedBy,
            food = foodId?.let { AdminMenuNameEstimateFood(it, contentStatus!!, !foodImageRef.isNullOrBlank(), it in requested) },
            deletedFoodId = if (foodId == null) deletedIds[nameKey] else null,
            promotable = promotedAt == null && contentStatus != FoodContentStatus.READY,
        )
    }
}

enum class AdminMenuNameEstimateSort { SCAN_COUNT, RECENT }

enum class AdminMenuNameEstimateFoodStatus { NONE, FAILED, PENDING_IMAGE, PENDING_REVIEW, READY }

data class AdminMenuNameEstimateFilter(
    val kind: MenuNameKind? = null,
    val promoted: Boolean? = null,
    val foodStatus: AdminMenuNameEstimateFoodStatus? = null,
    val sort: AdminMenuNameEstimateSort = AdminMenuNameEstimateSort.SCAN_COUNT,
)

enum class AdminMenuNameEstimatePromoteOutcome { CREATED_FOOD, CONTENT_REQUESTED, ALREADY_PENDING }

data class AdminMenuNameEstimatePromoteResult(
    val estimateId: Long,
    val foodId: Long,
    val outcome: AdminMenuNameEstimatePromoteOutcome,
    val promotedAt: LocalDateTime,
)
