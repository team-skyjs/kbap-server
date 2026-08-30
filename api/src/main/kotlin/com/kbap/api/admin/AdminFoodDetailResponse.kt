package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.util.ImageUrls
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

data class AdminFoodDetailResponse(
    val id: Long,
    val koreanName: String,
    val description: String,
    val longDescription: String?,
    val spiciness: Int,
    val contentStatus: FoodContentStatus,
    val contentFailureKind: FoodContentFailureKind?,
    val contentReviewRejectionReason: String?,
    val contentReviewAttempts: Int,
    val imageRef: String?,
    val imageUrl: String?,
    val nameTranslations: Map<String, String>,
    val descriptionTranslations: Map<String, String>,
    val ingredients: List<FoodIngredient>,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String): AdminFoodDetailResponse =
            AdminFoodDetailResponse(
                id = food.id,
                koreanName = food.displayName(LanguageCode.KO),
                description = food.description,
                longDescription = food.longDescription,
                spiciness = food.spiciness,
                contentStatus = food.contentStatus,
                contentFailureKind = food.contentFailureKind,
                contentReviewRejectionReason = food.contentReviewRejectionReason,
                contentReviewAttempts = food.contentReviewAttempts,
                imageRef = food.imageRef,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                nameTranslations = food.nameTranslations,
                descriptionTranslations = food.descriptionTranslations,
                ingredients = food.ingredients.orEmpty(),
                version = food.version,
                createdAt = food.createdAt,
                updatedAt = food.updatedAt,
            )
    }
}

data class AdminFoodUpdateRequest(
    @field:NotBlank
    val koreanName: String? = null,
    @field:NotNull
    val description: String? = null,
    @field:NotNull
    val spiciness: Int? = null,
    @field:NotNull
    val contentStatus: FoodContentStatus? = null,
    val imageRef: String? = null,
    val nameTranslations: Map<String, String>? = null,
    val descriptionTranslations: Map<String, String>? = null,
    val ingredients: List<FoodIngredient>? = null,
)

data class AdminFoodRecollectResponse(
    val requested: Long,
    val created: Long,
    val skipped: Long,
    val exceeded: Boolean,
    val max: Int,
) {
    companion object {
        fun from(result: AdminFoodRecollectResult): AdminFoodRecollectResponse =
            AdminFoodRecollectResponse(
                requested = result.requested,
                created = result.created,
                skipped = result.skipped,
                exceeded = result.exceeded,
                max = result.max,
            )
    }
}
