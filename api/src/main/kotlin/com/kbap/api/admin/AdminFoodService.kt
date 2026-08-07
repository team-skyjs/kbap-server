package com.kbap.api.admin

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.util.ImageUrls
import com.kbap.common.util.KoreanMenuNameNormalizer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFoodService(
    private val foodRepository: FoodJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
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

        food.koreanName = matchKey
        food.displayName = command.koreanName
        food.description = command.description
        food.spiciness = command.spiciness
        food.contentStatus = command.contentStatus
        food.imageRef = command.imageRef.takeIf { it.isNotBlank() }
        food.nameTranslations = nameTranslations
        food.descriptionTranslations = descriptionTranslations
        food.ingredients = ingredients
        return AdminFoodUpdateResult.UPDATED
    }

    private fun parseMap(json: String): Map<String, String> =
        json.takeIf { it.isNotBlank() }?.let { objectMapper.readValue<Map<String, String>>(it) } ?: emptyMap()

    @Transactional
    fun deleteFood(id: Long): AdminFoodDeleteResult {
        val food = foodRepository.findById(id).orElse(null) ?: return AdminFoodDeleteResult.NOT_FOUND
        food.delete()
        return AdminFoodDeleteResult.DELETED
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
        val created = if (newNames.isEmpty()) 0 else upsertAndResolve(newNames).size
        return SeedIncompleteResult(
            requested = requested,
            created = created,
            skipped = requested - created,
        )
    }

    private fun upsertAndResolve(displayNamesByMatchKey: Map<String, String>): Map<String, Food> {
        foodRepository.upsertIncomplete(
            displayNamesByMatchKey.map { (matchKey, displayName) -> Food.failed(matchKey, displayName) },
        )

        val matchKeys = displayNamesByMatchKey.keys
        val resolved = foodRepository.findByKoreanNameIn(matchKeys).associateBy { it.koreanName }
        val unresolved = matchKeys - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 등록 누락 — 소프트 삭제된 동명 음식이 있습니다: {}", unresolved)
        }
        return resolved
    }

    companion object {
        const val LIST_PAGE_SIZE = 200
    }
}

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
