package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.AdminFoodFilter
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.dto.AdminFoodRow
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class AdminFoodListItemResponse(
    val id: Long,
    val displayName: String,
    val koreanName: String,
    val contentStatus: AdminFoodStatusResponse,
    val contentFailureKind: String?,
    val spiciness: Int,
    val hasImage: Boolean,
    val imageUrl: String?,
    val contentReviewAttempts: Int,
    val reviewCount: Long,
    val vectorSyncStatus: String,
    val deleted: Boolean,
    val updatedAt: LocalDateTime,
)

data class AdminFoodListResponse(
    val items: List<AdminFoodListItemResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminIngredientCatalogResponse(
    val items: List<AdminIngredientCatalogItem>,
)

data class AdminIngredientCatalogItem(
    val code: String,
    val koreanName: String,
    val translations: Map<String, String>,
    val imageUrl: String?,
)

@Service
class AdminFoodQueryService(
    private val foodRepository: FoodJpaRepository,
    private val contentOutboxRepository: FoodContentOutboxJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val imageBatchRepository: ImageBatchJpaRepository,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val bookmarkRepository: BookmarkJpaRepository,
    private val ingredientRepository: IngredientJpaRepository,
    private val adminAuditQueryService: AdminAuditQueryService,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getFoodPage(filter: AdminFoodFilter, page: Int, size: Int): AdminFoodListResponse {
        val rows = foodRepository.findAdminPage(filter, page, size)
        val ids = rows.rows.map { it.id }
        val reviewCounts = if (ids.isEmpty()) emptyMap() else reviewRepository.aggregateRatingsByFoodIds(ids).associate { it.foodId to it.reviewCount }
        val vectorStatuses = if (ids.isEmpty()) emptyMap() else
            vectorOutboxRepository.findByFoodIdInOrderByIdDesc(ids).groupBy { it.foodId }.mapValues { it.value.first().outboxStatus.name }
        val totalPages = if (rows.totalCount == 0L) 0 else ((rows.totalCount - 1) / size + 1).toInt()
        return AdminFoodListResponse(
            items = rows.rows.map { toItem(it, reviewCounts[it.id] ?: 0L, vectorStatuses[it.id] ?: VECTOR_NONE) },
            page = page,
            size = size,
            totalCount = rows.totalCount,
            totalPages = totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getFoodDetail(id: Long): AdminFoodDetailResponse {
        val food = foodRepository.findByIdIncludingDeleted(id) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val items = imageBatchItemRepository.findTop10ByFoodIdOrderByIdDesc(id)
        val batches = imageBatchRepository.findAllById(items.map { it.batchId }.toSet()).associateBy { it.id }
        val rating = reviewRepository.aggregateRating(id, null)
        val history = AdminFoodHistoryResponse(
            contentOutboxes = contentOutboxRepository.findTop10ByFoodIdOrderByIdDesc(id).map {
                mapOf(
                    "id" to it.id, "status" to it.outboxStatus.name, "attempts" to it.attempts,
                    "createdAt" to it.createdAt, "sentAt" to it.sentAt, "lastError" to it.lastError, "lastFailedAt" to it.lastFailedAt,
                )
            },
            imageItems = items.map {
                val batch = batches[it.batchId]
                mapOf(
                    "itemId" to it.id, "batchId" to it.batchId, "openaiBatchId" to batch?.openaiBatchId,
                    "status" to it.itemStatus.name, "fileName" to it.fileName, "errorMsg" to it.errorMsg,
                    "submittedAt" to batch?.submittedAt,
                )
            },
            vectorOutboxes = vectorOutboxRepository.findTop5ByFoodIdOrderByIdDesc(id).map {
                mapOf(
                    "id" to it.id, "operation" to it.operation.name, "status" to it.outboxStatus.name,
                    "attempts" to it.attempts, "lastError" to it.lastError, "updatedAt" to it.updatedAt,
                )
            },
            reviewSummary = mapOf("count" to rating.reviewCount, "averageRating" to rating.average),
            scanMatchCount = scanHistoryRepository.countByFoodId(id),
            bookmarkCount = bookmarkRepository.countByFoodId(id),
            auditLogs = adminAuditQueryService.getRecentLogsForTarget(AdminAuditTargetType.FOOD, id, RECENT_AUDIT_LOGS),
        )
        return AdminFoodDetailResponse.from(food, imagePublicBaseUrl, history)
    }

    @Transactional(readOnly = true)
    fun getIngredients(): AdminIngredientCatalogResponse =
        AdminIngredientCatalogResponse(
            ingredientRepository.findAllByOrderByCode().map {
                AdminIngredientCatalogItem(
                    code = it.code.name,
                    koreanName = it.koreanName,
                    translations = it.translations,
                    imageUrl = ImageUrls.resolve(imagePublicBaseUrl, it.imagePath),
                )
            },
        )

    private fun toItem(row: AdminFoodRow, reviewCount: Long, vectorSyncStatus: String) =
        AdminFoodListItemResponse(
            id = row.id,
            displayName = row.displayName,
            koreanName = row.koreanName,
            contentStatus = AdminFoodStatusResponse.from(row.contentStatus),
            contentFailureKind = row.contentFailureKind?.name,
            spiciness = row.spiciness,
            hasImage = !row.imageRef.isNullOrBlank(),
            imageUrl = ImageUrls.resolve(imagePublicBaseUrl, row.imageRef),
            contentReviewAttempts = row.contentReviewAttempts,
            reviewCount = reviewCount,
            vectorSyncStatus = vectorSyncStatus,
            deleted = row.deleted,
            updatedAt = row.updatedAt,
        )

    companion object {
        const val VECTOR_NONE = "NONE"
        const val RECENT_AUDIT_LOGS = 10
    }
}
