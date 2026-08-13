package com.kbap.api.admin

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentStatus
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
    private val foodService: FoodService,
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
    fun getFoodDetailOrNull(id: Long): AdminFoodDetailView? {
        val food = foodRepository.findById(id).orElse(null) ?: return null
        return AdminFoodDetailView.from(food, imagePublicBaseUrl, objectMapper.writerWithDefaultPrettyPrinter()::writeValueAsString)
    }

    @Transactional
    fun updateFood(id: Long, command: UpdateFoodCommand): AdminFoodUpdateResult {
        val food = foodRepository.findById(id).orElse(null) ?: return AdminFoodUpdateResult.NOT_FOUND
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

        val matchKey = KoreanMenuNameNormalizer.matchKey(command.koreanName)
        if (matchKey.isEmpty()) return AdminFoodUpdateResult.INVALID_NAME

        val duplicated = foodRepository.findByKoreanNameIn(setOf(matchKey))
            .any { it.id != food.id }
        if (duplicated) return AdminFoodUpdateResult.DUPLICATE_NAME

        val wasReady = food.isReady()
        food.koreanName = matchKey
        food.displayName = command.koreanName
        food.description = command.description
        food.spiciness = command.spiciness
        food.contentStatus = command.contentStatus
        food.imageRef = command.imageRef.takeIf { it.isNotBlank() }
        food.nameTranslations = nameTranslations
        food.descriptionTranslations = descriptionTranslations
        food.ingredients = ingredients
        when {
            food.isReady() -> vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
            wasReady -> vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.DELETE)
        }
        return AdminFoodUpdateResult.UPDATED
    }

    private fun parseMap(json: String): Map<String, String> =
        json.takeIf { it.isNotBlank() }?.let { objectMapper.readValue<Map<String, String>>(it) } ?: emptyMap()

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
        val newNames = displayNamesByMatchKey - existing
        val created = if (newNames.isEmpty()) 0 else foodService.createIncomplete(newNames).size
        return SeedIncompleteResult(
            requested = requested,
            created = created,
            skipped = requested - created,
        )
    }

    companion object {
        const val LIST_PAGE_SIZE = 200

        const val RECOLLECT_MAX = 500
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
    INVALID_NAME,
    INVALID_JSON,
    DUPLICATE_NAME,
}

data class UpdateFoodCommand(
    val koreanName: String,
    val description: String,
    val spiciness: Int,
    val contentStatus: FoodContentStatus,
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
