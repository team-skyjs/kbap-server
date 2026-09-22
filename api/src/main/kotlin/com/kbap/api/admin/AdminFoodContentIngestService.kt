package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodContentIngestService(
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val foodIngredientRepository: FoodIngredientJdbcRepository,
) {
    @Transactional
    fun ingestContent(
        outboxId: Long,
        foodId: Long,
        description: String,
        longDescription: String?,
        spiciness: Int,
        nameTranslations: Map<String, String>,
        descriptionTranslations: Map<String, String>,
        ingredients: List<FoodIngredient>,
    ) {
        if (!completeOutbox(outboxId, foodId)) return
        val food = getFood(foodId)
        food.applyContent(
            description = description,
            longDescription = longDescription,
            spiciness = spiciness,
            nameTranslations = nameTranslations,
            descriptionTranslations = descriptionTranslations,
            ingredients = ingredients,
        )
        foodIngredientRepository.replace(foodId, food.ingredients)
    }

    @Transactional
    fun ingestFailure(outboxId: Long, foodId: Long, failureKind: FoodContentFailureKind, reason: String) {
        if (!completeOutbox(outboxId, foodId)) return
        getFood(foodId).recordContentFailure(failureKind, reason)
    }

    private fun completeOutbox(outboxId: Long, foodId: Long): Boolean {
        if (outboxRepository.completeIfProcessable(outboxId, foodId) == 1) {
            return true
        }
        if (outboxRepository.existsByIdAndFoodIdAndDeadAtIsNotNull(outboxId, foodId)) {
            log.warn("포기한 요청의 결과가 도착해 버린다 — outboxId={}, foodId={}", outboxId, foodId)
            return false
        }
        if (outboxRepository.countSuperseded(outboxId, foodId) > 0) {
            log.warn("더 새 요청이 있는 옛 요청의 결과가 도착해 버린다 — outboxId={}, foodId={}", outboxId, foodId)
            return false
        }
        if (
            outboxRepository.existsByIdAndFoodIdAndOutboxStatus(
                outboxId,
                foodId,
                FoodContentOutboxStatus.COMPLETE,
            )
        ) {
            throw BusinessException(ErrorCode.FOOD_CONTENT_REQUEST_ALREADY_COMPLETED)
        }
        if (outboxRepository.existsById(outboxId) && !foodRepository.existsById(foodId)) {
            throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        }
        throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AdminFoodContentIngestService::class.java)
    }

    private fun getFood(foodId: Long): Food =
        foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
}
