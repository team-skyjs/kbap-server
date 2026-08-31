package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.util.ImageUrls
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
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
    val ingredients: List<FoodIngredient>?,
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
                ingredients = food.ingredients,
                version = food.version,
                createdAt = food.createdAt,
                updatedAt = food.updatedAt,
            )
    }
}

data class AdminFoodUpdateRequest(
    @field:NotBlank
    @field:Size(max = 255, message = "koreanName 은 255자 이하여야 합니다")
    val koreanName: String? = null,
    @field:NotNull
    @field:Size(max = 255, message = "description 은 255자 이하여야 합니다")
    val description: String? = null,
    @field:NotNull
    @field:Min(-1, message = "spiciness 는 -1(미조사) 이상이어야 합니다")
    @field:Max(10, message = "spiciness 는 10 이하여야 합니다")
    val spiciness: Int? = null,
    @field:NotNull
    val contentStatus: FoodContentStatus? = null,
    @field:Size(max = 500, message = "imageRef 는 500자 이하여야 합니다")
    val imageRef: String? = null,
    val nameTranslations: Map<String, String>? = null,
    val descriptionTranslations: Map<String, String>? = null,
    val ingredients: List<FoodIngredient>? = null,
    @field:NotNull
    val version: Long? = null,
) {
    @AssertTrue(message = "ingredients 의 code 는 성분 카탈로그 코드여야 합니다")
    fun isIngredientCodesKnown(): Boolean = ingredients.orEmpty().all { it.code in KNOWN_INGREDIENT_CODES }

    companion object {
        private val KNOWN_INGREDIENT_CODES: Set<String> = IngredientCode.entries.map { it.name }.toSet()
    }
}

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
