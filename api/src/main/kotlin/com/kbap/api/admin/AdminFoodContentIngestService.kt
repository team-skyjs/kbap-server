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
        completeOutbox(outboxId, foodId)
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
        completeOutbox(outboxId, foodId)
        getFood(foodId).recordContentFailure(failureKind, reason)
    }

    private fun completeOutbox(outboxId: Long, foodId: Long) {
        if (outboxRepository.completeIfProcessable(outboxId, foodId) == 1) {
            return
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
        throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    private fun getFood(foodId: Long): Food =
        foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
}
