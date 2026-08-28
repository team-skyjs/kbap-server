package com.kbap.api.admin

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.food.FoodService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.AdminFoodFilter
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.dto.AdminFoodRow
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodTransition
import com.kbap.common.domain.food.model.FoodTransitionException
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.util.ImageUrls
import com.kbap.common.util.KoreanMenuNameNormalizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFoodService(
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val imageBatchRepository: ImageBatchJpaRepository,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val bookmarkRepository: BookmarkJpaRepository,
    private val ingredientRepository: IngredientJpaRepository,
    private val foodService: FoodService,
    private val auditRecorder: AdminAuditRecorder,
    private val adminAuditLogService: AdminAuditLogService,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val objectMapper = jacksonObjectMapper()

    @Transactional(readOnly = true)
    fun getFoodPage(page: Int, query: String? = null, status: FoodContentStatus? = null): AdminFoodListPageView {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(page - 1, LIST_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))
        val result = when {
            keyword == null && status == null -> foodRepository.findAll(pageable)
            keyword == null -> foodRepository.findByContentStatus(status!!, pageable)
            status == null -> foodRepository.findByDisplayNameContaining(keyword, pageable)
            else -> foodRepository.findByDisplayNameContainingAndContentStatus(keyword, status, pageable)
        }
        return AdminFoodListPageView(
            items = result.content.map { AdminFoodSummaryView.from(it, imagePublicBaseUrl) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
            query = keyword,
            status = status,
        )
    }

    @Transactional(readOnly = true)
    fun getFoodPage(filter: AdminFoodFilter, page: Int, size: Int): AdminFoodListResponse {
        val rows = foodRepository.findAdminPage(filter, page, size)
        val ids = rows.rows.map { it.id }
        val reviewCounts = if (ids.isEmpty()) emptyMap() else reviewRepository.aggregateRatingsByFoodIds(ids).associate { it.foodId to it.reviewCount }
        val vectorStatuses = if (ids.isEmpty()) emptyMap() else
            vectorOutboxRepository.findByFoodIdInOrderByIdDesc(ids).groupBy { it.foodId }.mapValues { it.value.first().outboxStatus.name }
        return AdminFoodListResponse(
            items = rows.rows.map { toListItem(it, reviewCounts[it.id] ?: 0L, vectorStatuses[it.id] ?: VECTOR_NONE) },
            page = page,
            size = size,
            totalCount = rows.totalCount,
            totalPages = totalPagesOf(rows.totalCount, size),
        )
    }

    @Transactional(readOnly = true)
    fun getFoodDetailOrNull(id: Long): AdminFoodDetailView? {
        val food = foodRepository.findById(id).orElse(null) ?: return null
        return AdminFoodDetailView.from(food, imagePublicBaseUrl, objectMapper.writerWithDefaultPrettyPrinter()::writeValueAsString)
    }

    @Transactional(readOnly = true)
    fun getFoodDetail(id: Long): AdminFoodDetailResponse {
        val food = foodRepository.findByIdIncludingDeleted(id) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val items = imageBatchItemRepository.findTop10ByFoodIdOrderByIdDesc(id)
        val batches = imageBatchRepository.findAllById(items.map { it.batchId }.toSet()).associateBy { it.id }
        val rating = reviewRepository.aggregateRating(id, null)
        val history = AdminFoodHistoryResponse(
            contentOutboxes = outboxRepository.findTop10ByFoodIdOrderByIdDesc(id).map {
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
            auditLogs = adminAuditLogService.getRecentLogsForTarget(AdminAuditTargetType.FOOD, id, RECENT_AUDIT_LOGS),
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

    @Transactional
    fun updateFood(id: Long, command: UpdateFoodCommand, expectedVersion: Long, adminId: Long): AdminFoodUpdateResult {
        val food = foodRepository.findById(id).orElse(null) ?: return AdminFoodUpdateResult.NOT_FOUND
        if (expectedVersion != food.version) return AdminFoodUpdateResult.STALE
        if (command.koreanName.isBlank()) return AdminFoodUpdateResult.INVALID_NAME

        val nameTranslations: Map<String, String>
        val descriptionTranslations: Map<String, String>
        val ingredients: List<FoodIngredient>?
        try {
            nameTranslations = parseMap(command.nameTranslationsJson)
            descriptionTranslations = parseMap(command.descriptionTranslationsJson)
            ingredients = command.ingredientsJson
                .takeIf { it.isNotBlank() }
                ?.let { objectMapper.readValue<List<FoodIngredient>>(it) }
        } catch (e: JacksonException) {
            return AdminFoodUpdateResult.INVALID_JSON
        }

        val errors = FoodContentValidator.validate(
            FoodContentCandidate(
                koreanName = command.koreanName,
                description = command.description,
                longDescription = food.longDescription,
                spiciness = command.spiciness,
                nameTranslations = nameTranslations,
                descriptionTranslations = descriptionTranslations,
                ingredients = ingredients,
            ),
            requireComplete = requiresCompleteContent(food),
        )
        if (errors.any { it.field == "koreanName" }) return AdminFoodUpdateResult.INVALID_NAME
        if (errors.isNotEmpty()) return AdminFoodUpdateResult.INVALID_CONTENT

        val matchKey = KoreanMenuNameNormalizer.matchKey(command.koreanName)
        val duplicated = foodRepository.findByKoreanNameIn(setOf(matchKey))
            .any { it.id != food.id }
        if (duplicated) return AdminFoodUpdateResult.DUPLICATE_NAME

        val before = snapshot(food)
        food.koreanName = matchKey
        food.displayName = command.koreanName
        food.description = command.description
        food.spiciness = command.spiciness
        food.imageRef = command.imageRef.takeIf { it.isNotBlank() }
        food.nameTranslations = nameTranslations
        food.descriptionTranslations = descriptionTranslations
        food.ingredients = ingredients
        if (food.isReady()) vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
        auditRecorder.record(adminId, AdminAuditAction.FOOD_UPDATE, AdminAuditTargetType.FOOD, food.id, before, snapshot(food))
        return AdminFoodUpdateResult.UPDATED
    }

    @Transactional
    fun updateFood(adminId: Long, id: Long, request: AdminFoodUpdateRequest): AdminFoodDetailResponse {
        val food = getFood(id)
        if (request.version != food.version) {
            throw BusinessException(ErrorCode.CONFLICT, payload = mapOf("currentVersion" to food.version))
        }
        val ingredients = request.ingredients?.map { it.toIngredient() }
        val errors = FoodContentValidator.validate(
            FoodContentCandidate(
                koreanName = request.koreanName,
                description = request.description,
                longDescription = request.longDescription,
                spiciness = request.spiciness,
                nameTranslations = request.nameTranslations,
                descriptionTranslations = request.descriptionTranslations,
                ingredients = ingredients,
            ),
            requireComplete = requiresCompleteContent(food),
        )
        if (errors.isNotEmpty()) throw BusinessException(ErrorCode.FOOD_INVALID_CONTENT, payload = mapOf("errors" to errors))

        val displayName = request.koreanName!!.trim()
        val matchKey = KoreanMenuNameNormalizer.matchKey(displayName)
        if (foodRepository.findByKoreanNameIn(setOf(matchKey)).any { it.id != food.id }) {
            throw BusinessException(ErrorCode.DUPLICATE_FOOD_NAME)
        }

        val before = snapshot(food)
        food.koreanName = matchKey
        food.displayName = displayName
        food.description = request.description!!
        food.longDescription = request.longDescription
        food.spiciness = request.spiciness!!
        food.imageRef = request.imageRef?.trim()?.takeIf { it.isNotEmpty() }
        food.nameTranslations = request.nameTranslations!!
        food.descriptionTranslations = request.descriptionTranslations!!
        food.ingredients = ingredients
        foodRepository.flush()
        if (food.isReady()) vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
        auditRecorder.record(adminId, AdminAuditAction.FOOD_UPDATE, AdminAuditTargetType.FOOD, food.id, before, snapshot(food))
        return AdminFoodDetailResponse.from(food, imagePublicBaseUrl)
    }

    @Transactional
    fun approve(adminId: Long, id: Long): AdminFoodTransitionResponse {
        val food = getFood(id)
        if (food.isReady()) return AdminFoodTransitionResponse.from(food)
        return applyTransition(adminId, food, FoodTransition.APPROVE, null, AdminAuditAction.FOOD_APPROVE)
    }

    @Transactional
    fun reject(adminId: Long, id: Long, reason: String): AdminFoodTransitionResponse =
        applyTransition(adminId, getFood(id), FoodTransition.REJECT, reason, AdminAuditAction.FOOD_REJECT)

    @Transactional
    fun transition(adminId: Long, id: Long, transition: FoodTransition, reason: String?): AdminFoodTransitionResponse {
        val action = when (transition) {
            FoodTransition.APPROVE -> AdminAuditAction.FOOD_APPROVE
            FoodTransition.REJECT -> AdminAuditAction.FOOD_REJECT
            else -> AdminAuditAction.FOOD_TRANSITION
        }
        return applyTransition(adminId, getFood(id), transition, reason, action)
    }

    @Transactional
    fun deleteFood(adminId: Long, id: Long): AdminFoodTransitionResponse {
        val food = foodRepository.findByIdIncludingDeleted(id) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        if (food.isDeleted()) return AdminFoodTransitionResponse.from(food)
        food.delete()
        vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.DELETE)
        auditRecorder.record(adminId, AdminAuditAction.FOOD_DELETE, AdminAuditTargetType.FOOD, food.id, mapOf("deleted" to false), mapOf("deleted" to true))
        return AdminFoodTransitionResponse.from(food)
    }

    @Transactional
    fun restoreFood(adminId: Long, id: Long): AdminFoodDetailResponse {
        val deleted = foodRepository.findByIdIncludingDeleted(id) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        if (deleted.isDeleted()) {
            foodRepository.restore(id)
            auditRecorder.record(adminId, AdminAuditAction.FOOD_RESTORE, AdminAuditTargetType.FOOD, id, mapOf("deleted" to true), mapOf("deleted" to false))
        }
        val food = getFood(id)
        if (food.isReady()) vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
        return AdminFoodDetailResponse.from(food, imagePublicBaseUrl)
    }

    @Transactional
    fun deleteFood(id: Long): AdminFoodDeleteResult {
        val food = foodRepository.findById(id).orElse(null) ?: return AdminFoodDeleteResult.NOT_FOUND
        food.delete()
        vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.DELETE)
        return AdminFoodDeleteResult.DELETED
    }

    @Transactional
    fun requestRecollect(
        query: String?,
        status: FoodContentStatus?,
        max: Int = RECOLLECT_MAX,
    ): AdminFoodRecollectResult {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        val requested = countRecollectTargets(keyword, status)
        if (requested == 0L) return AdminFoodRecollectResult(requested = 0, created = 0, skipped = 0, max = max)
        if (requested > max) {
            return AdminFoodRecollectResult(requested = requested, created = 0, skipped = 0, exceeded = true, max = max)
        }

        val targets = getRecollectTargets(keyword, status)
        val alreadyPending = outboxRepository
            .findByFoodIdInAndOutboxStatus(targets.map { it.id }, FoodContentOutboxStatus.PENDING)
            .map { it.foodId }
            .toSet()
        val created = targets.filterNot { it.id in alreadyPending }
        outboxRepository.saveAll(created.map { FoodContentOutbox.pending(it.id, it.displayName) })

        return AdminFoodRecollectResult(
            requested = requested,
            created = created.size.toLong(),
            skipped = requested - created.size,
            max = max,
        )
    }

    @Transactional
    fun seedIncomplete(koreanNames: Set<String>): SeedIncompleteResult {
        val displayNamesByMatchKey = koreanNames
            .map { KoreanMenuNameNormalizer.matchKey(it) to it }
            .filter { (matchKey, _) -> matchKey.isNotEmpty() }
            .distinctBy { (matchKey, _) -> matchKey }
            .toMap()
        if (displayNamesByMatchKey.isEmpty()) return SeedIncompleteResult(requested = 0, created = 0, skipped = 0)

        val requested = displayNamesByMatchKey.size
        val existing = foodRepository.findByKoreanNameIn(displayNamesByMatchKey.keys).map { it.koreanName }.toSet()
        val blockedByDeleted = foodRepository.findDeletedKoreanNamesIn(displayNamesByMatchKey.keys - existing).toSet()
        val newNames = displayNamesByMatchKey - existing - blockedByDeleted
        val createdFoods = if (newNames.isEmpty()) emptyMap() else foodService.createIncomplete(newNames)
        val created = createdFoods.size
        return SeedIncompleteResult(
            requested = requested,
            created = created,
            skipped = requested - created,
            createdIds = createdFoods.values.map { it.id },
            skippedNames = displayNamesByMatchKey.filterKeys { it in existing }.values.toList(),
            blockedByDeletedNames = displayNamesByMatchKey.filterKeys { it in blockedByDeleted }.values.toList(),
        )
    }

    private fun applyTransition(
        adminId: Long,
        food: Food,
        transition: FoodTransition,
        reason: String?,
        action: AdminAuditAction,
    ): AdminFoodTransitionResponse {
        val before = food.contentStatus
        try {
            food.transition(transition, reason)
        } catch (e: FoodTransitionException) {
            throw BusinessException(
                ErrorCode.FOOD_INVALID_TRANSITION,
                payload = mapOf("reason" to e.reason, "allowed" to e.allowed.map { it.name }),
            )
        }
        when (transition) {
            FoodTransition.APPROVE -> vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
            FoodTransition.UNPUBLISH -> vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.DELETE)
            else -> Unit
        }
        auditRecorder.record(
            adminId,
            action,
            AdminAuditTargetType.FOOD,
            food.id,
            mapOf("contentStatus" to before.name),
            mapOf("contentStatus" to food.contentStatus.name),
            note = reason ?: transition.name,
        )
        return AdminFoodTransitionResponse.from(food)
    }

    private fun getFood(id: Long): Food =
        foodRepository.findById(id).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }

    private fun parseMap(json: String): Map<String, String> =
        json.takeIf { it.isNotBlank() }?.let { objectMapper.readValue<Map<String, String>>(it) } ?: emptyMap()

    private fun countRecollectTargets(keyword: String?, status: FoodContentStatus?): Long = when {
        keyword == null && status == null -> foodRepository.count()
        keyword == null -> foodRepository.countByContentStatus(status!!)
        status == null -> foodRepository.countByDisplayNameContaining(keyword)
        else -> foodRepository.countByDisplayNameContainingAndContentStatus(keyword, status)
    }

    private fun getRecollectTargets(keyword: String?, status: FoodContentStatus?): List<Food> = when {
        keyword == null && status == null -> foodRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
        keyword == null -> foodRepository.findByContentStatusOrderByIdAsc(status!!, Pageable.unpaged())
        status == null -> foodRepository.findByDisplayNameContainingOrderByIdAsc(keyword)
        else -> foodRepository.findByContentStatusAndDisplayNameContainingOrderByIdAsc(status, keyword)
    }

    private fun toListItem(row: AdminFoodRow, reviewCount: Long, vectorSyncStatus: String) =
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
        const val LIST_PAGE_SIZE = 200

        const val RECOLLECT_MAX = 500

        const val VECTOR_NONE = "NONE"

        const val RECENT_AUDIT_LOGS = 10

        fun requiresCompleteContent(food: Food): Boolean =
            food.contentStatus == FoodContentStatus.READY || food.contentStatus == FoodContentStatus.PENDING_REVIEW

        fun snapshot(food: Food): Map<String, Any?> = mapOf(
            "koreanName" to food.koreanName,
            "displayName" to food.displayName,
            "description" to food.description,
            "longDescription" to food.longDescription,
            "spiciness" to food.spiciness,
            "imageRef" to food.imageRef,
            "nameTranslations" to food.nameTranslations,
            "descriptionTranslations" to food.descriptionTranslations,
            "ingredients" to food.ingredients?.map { mapOf("code" to it.code, "inclusionPercent" to it.inclusionPercent) },
        )
    }
}

data class AdminFoodRecollectResult(
    val requested: Long,
    val created: Long,
    val skipped: Long,
    val exceeded: Boolean = false,
    val max: Int = AdminFoodService.RECOLLECT_MAX,
)

enum class AdminFoodDeleteResult {
    DELETED,
    NOT_FOUND,
}

enum class AdminFoodUpdateResult {
    UPDATED,
    NOT_FOUND,
    STALE,
    INVALID_NAME,
    INVALID_JSON,
    INVALID_CONTENT,
    DUPLICATE_NAME,
}

data class UpdateFoodCommand(
    val koreanName: String,
    val description: String,
    val spiciness: Int,
    val imageRef: String,
    val nameTranslationsJson: String,
    val descriptionTranslationsJson: String,
    val ingredientsJson: String,
)

data class AdminFoodListPageView(
    val items: List<AdminFoodSummaryView>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val query: String? = null,
    val status: FoodContentStatus? = null,
)

data class AdminFoodSummaryView(
    val id: Long,
    val koreanName: String,
    val contentStatus: FoodContentStatus,
    val contentFailureKind: FoodContentFailureKind?,
    val spiciness: Int,
    val imageUrl: String?,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String): AdminFoodSummaryView =
            AdminFoodSummaryView(
                id = food.id,
                koreanName = food.displayName(LanguageCode.KO),
                contentStatus = food.contentStatus,
                contentFailureKind = food.contentFailureKind,
                spiciness = food.spiciness,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                updatedAt = food.updatedAt,
            )
    }
}

data class AdminFoodDetailView(
    val id: Long,
    val koreanName: String,
    val description: String,
    val spiciness: Int,
    val contentStatus: FoodContentStatus,
    val contentFailureKind: FoodContentFailureKind?,
    val contentReviewRejectionReason: String?,
    val imageRef: String?,
    val imageUrl: String?,
    val nameTranslationsJson: String,
    val descriptionTranslationsJson: String,
    val ingredientsJson: String,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String, toJson: (Any) -> String): AdminFoodDetailView =
            AdminFoodDetailView(
                id = food.id,
                koreanName = food.displayName(LanguageCode.KO),
                description = food.description,
                spiciness = food.spiciness,
                contentStatus = food.contentStatus,
                contentFailureKind = food.contentFailureKind,
                contentReviewRejectionReason = food.contentReviewRejectionReason,
                imageRef = food.imageRef,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                nameTranslationsJson = toJson(food.nameTranslations),
                descriptionTranslationsJson = toJson(food.descriptionTranslations),
                ingredientsJson = food.ingredients?.let(toJson) ?: "",
                version = food.version,
                createdAt = food.createdAt,
                updatedAt = food.updatedAt,
            )
    }
}
