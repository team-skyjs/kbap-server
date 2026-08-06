package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.util.ImageUrls

data class AdminFoodContentReviewTargetsResponse(
    val items: List<AdminFoodContentReviewTarget>,
) {
    companion object {
        fun from(foods: List<Food>, imagePublicBaseUrl: String): AdminFoodContentReviewTargetsResponse =
            AdminFoodContentReviewTargetsResponse(foods.map { AdminFoodContentReviewTarget.from(it, imagePublicBaseUrl) })
    }
}

data class AdminFoodContentReviewTarget(
    val foodId: Long,
    val koreanName: String,
    val description: String,
    val nameTranslations: Map<String, String>,
    val descriptionTranslations: Map<String, String>,
    val avoidanceSubstances: List<FoodAvoidanceItem>,
    val spiciness: Int,
    val imageUrl: String?,
    val contentReviewAttempts: Int,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String): AdminFoodContentReviewTarget =
            AdminFoodContentReviewTarget(
                foodId = food.id,
                koreanName = food.displayName(LanguageCode.KO),
                description = food.description,
                nameTranslations = food.nameTranslations,
                descriptionTranslations = food.descriptionTranslations,
                avoidanceSubstances = food.avoidanceSubstances.orEmpty(),
                spiciness = food.spiciness,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                contentReviewAttempts = food.contentReviewAttempts,
            )
    }
}

data class AdminFoodContentReviewResultResponse(
    val foodId: Long,
    val contentStatus: FoodContentStatus,
    val contentReviewAttempts: Int,
    val contentReviewRejectionReason: String?,
) {
    companion object {
        fun from(food: Food): AdminFoodContentReviewResultResponse =
            AdminFoodContentReviewResultResponse(
                foodId = food.id,
                contentStatus = food.contentStatus,
                contentReviewAttempts = food.contentReviewAttempts,
                contentReviewRejectionReason = food.contentReviewRejectionReason,
            )
    }
}
