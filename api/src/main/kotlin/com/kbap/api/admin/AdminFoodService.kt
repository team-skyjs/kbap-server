package com.kbap.api.admin

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
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
    fun getFoodPage(page: Int): AdminFoodListPageView {
        val pageable = PageRequest.of(page - 1, LIST_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))
        val result = foodRepository.findAll(pageable)
        return AdminFoodListPageView(
            items = result.content.map { AdminFoodSummaryView.from(it) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
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
        val avoidanceSubstances: List<FoodAvoidanceItem>?
        try {
            nameTranslations = parseMap(command.nameTranslationsJson)
            descriptionTranslations = parseMap(command.descriptionTranslationsJson)
            avoidanceSubstances = command.avoidanceSubstancesJson
                .takeIf { it.isNotBlank() }
                ?.let { objectMapper.readValue<List<FoodAvoidanceItem>>(it) }
        } catch (e: JacksonException) {
            return AdminFoodUpdateResult.INVALID_JSON
        }

        val duplicated = foodRepository.findByKoreanNameIn(setOf(command.koreanName))
            .any { it.id != food.id }
        if (duplicated) return AdminFoodUpdateResult.DUPLICATE_NAME

        food.koreanName = command.koreanName
        food.description = command.description
        food.spiciness = command.spiciness
        food.contentStatus = command.contentStatus
        food.imageRef = command.imageRef.takeIf { it.isNotBlank() }
        food.nameTranslations = nameTranslations
        food.descriptionTranslations = descriptionTranslations
        food.avoidanceSubstances = avoidanceSubstances
        food.transitionByContentState()
        return AdminFoodUpdateResult.UPDATED
    }

    private fun parseMap(json: String): Map<String, String> =
        json.takeIf { it.isNotBlank() }?.let { objectMapper.readValue<Map<String, String>>(it) } ?: emptyMap()

    @Transactional
    fun seedIncomplete(koreanNames: Set<String>): SeedIncompleteResult {
        val names = koreanNames
            .map { KoreanMenuNameNormalizer.matchKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return SeedIncompleteResult(requested = 0, created = 0, skipped = 0)

        val existing = foodRepository.findByKoreanNameIn(names).map { it.koreanName }.toSet()
        val newNames = names - existing
        val created = if (newNames.isEmpty()) 0 else upsertAndResolve(newNames).size
        return SeedIncompleteResult(
            requested = names.size,
            created = created,
            skipped = names.size - created,
        )
    }

    private fun upsertAndResolve(koreanNames: Set<String>): Map<String, Food> {
        foodRepository.upsertIncomplete(koreanNames.map { Food.incomplete(it) })

        val resolved = foodRepository.findByKoreanNameIn(koreanNames).associateBy { it.koreanName }
        val unresolved = koreanNames - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 등록 누락 — 소프트 삭제된 동명 음식이 있습니다: {}", unresolved)
        }
        return resolved
    }

    companion object {
        const val LIST_PAGE_SIZE = 200
    }
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
    val avoidanceSubstancesJson: String,
)

data class AdminFoodListPageView(
    val items: List<AdminFoodSummaryView>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminFoodSummaryView(
    val id: Long,
    val koreanName: String,
    val contentStatus: FoodContentStatus,
    val spiciness: Int,
    val hasImage: Boolean,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(food: Food): AdminFoodSummaryView =
            AdminFoodSummaryView(
                id = food.id,
                koreanName = food.koreanName,
                contentStatus = food.contentStatus,
                spiciness = food.spiciness,
                hasImage = !food.imageRef.isNullOrBlank(),
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
    val avoidanceSubstancesJson: String,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String, toJson: (Any) -> String): AdminFoodDetailView =
            AdminFoodDetailView(
                id = food.id,
                koreanName = food.koreanName,
                description = food.description,
                spiciness = food.spiciness,
                contentStatus = food.contentStatus,
                imageRef = food.imageRef,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                nameTranslationsJson = toJson(food.nameTranslations),
                descriptionTranslationsJson = toJson(food.descriptionTranslations),
                avoidanceSubstancesJson = food.avoidanceSubstances?.let(toJson) ?: "",
                version = food.version,
                createdAt = food.createdAt,
                updatedAt = food.updatedAt,
            )
    }
}
