package com.kbap.api.admin

import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.util.ImageUrls

data class AdminFoodReviewTargetsResponse(
    val items: List<AdminFoodReviewTarget>,
) {
    companion object {
        fun from(foods: List<Food>, imagePublicBaseUrl: String): AdminFoodReviewTargetsResponse =
            AdminFoodReviewTargetsResponse(foods.map { AdminFoodReviewTarget.from(it, imagePublicBaseUrl) })
    }
}

data class AdminFoodReviewTarget(
    val foodId: Long,
    val koreanName: String,
    val description: String,
    val nameTranslations: Map<String, String>,
    val descriptionTranslations: Map<String, String>,
    val avoidanceSubstances: List<FoodAvoidanceItem>,
    val spiciness: Int,
    val imageUrl: String?,
    val reviewAttempts: Int,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String): AdminFoodReviewTarget =
            AdminFoodReviewTarget(
                foodId = food.id,
                koreanName = food.koreanName,
                description = food.description,
                nameTranslations = food.nameTranslations,
                descriptionTranslations = food.descriptionTranslations,
                avoidanceSubstances = food.avoidanceSubstances.orEmpty(),
                spiciness = food.spiciness,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                reviewAttempts = food.reviewAttempts,
            )
    }
}

data class AdminFoodReviewResultResponse(
    val foodId: Long,
    val contentStatus: FoodContentStatus,
    val reviewAttempts: Int,
    val reviewRejectionReason: String?,
) {
    companion object {
        fun from(food: Food): AdminFoodReviewResultResponse =
            AdminFoodReviewResultResponse(
                foodId = food.id,
                contentStatus = food.contentStatus,
                reviewAttempts = food.reviewAttempts,
                reviewRejectionReason = food.reviewRejectionReason,
            )
    }
}
