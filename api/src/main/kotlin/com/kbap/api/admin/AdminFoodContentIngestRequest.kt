package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodIngredient
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class AdminFoodContentIngestRequest(
    @field:NotNull
    @field:Positive
    val outboxId: Long? = null,

    @field:NotNull
    @field:Positive
    val foodId: Long? = null,

    val displayName: String? = null,

    @field:NotNull
    val passed: Boolean? = null,

    val description: String? = null,
    val longDescription: String? = null,
    val spiciness: Int? = null,
    val nameTranslations: Map<String, String>? = null,
    val descriptionTranslations: Map<String, String>? = null,
    val ingredients: List<FoodIngredient>? = null,
    val failureKind: FoodContentFailureKind? = null,
    val reason: String? = null,
) {
    private val contentErrors: List<FieldError> by lazy {
        if (!isPassed()) {
            emptyList()
        } else {
            FoodContentValidator.validate(
                FoodContentCandidate(
                    description = description,
                    longDescription = longDescription,
                    spiciness = spiciness,
                    nameTranslations = nameTranslations,
                    descriptionTranslations = descriptionTranslations,
                    ingredients = ingredients,
                ),
            )
        }
    }

    @AssertTrue(message = "description 은 1~255자여야 합니다")
    fun isDescriptionValid(): Boolean = contentErrors.none { it.field == "description" }

    @AssertTrue(message = "longDescription 은 1000자 이하여야 합니다")
    fun isLongDescriptionValid(): Boolean = contentErrors.none { it.field == "longDescription" }

    @AssertTrue(message = "spiciness 는 0~10 이어야 합니다")
    fun isSpicinessValid(): Boolean = contentErrors.none { it.field == "spiciness" }

    @AssertTrue(message = "nameTranslations 는 9개 대상 언어를 모두 채워야 합니다")
    fun isNameTranslationsValid(): Boolean = contentErrors.none { it.field.startsWith("nameTranslations") }

    @AssertTrue(message = "descriptionTranslations 는 9개 대상 언어를 모두 채워야 합니다")
    fun isDescriptionTranslationsValid(): Boolean = contentErrors.none { it.field.startsWith("descriptionTranslations") }

    @AssertTrue(message = "ingredients 는 필수입니다(해당 없음은 빈 배열)")
    fun isIngredientsPresent(): Boolean = contentErrors.none { it.code == "INGREDIENTS_MISSING" }

    @AssertTrue(message = "ingredients 의 code 는 성분 카탈로그 코드여야 합니다")
    fun isIngredientCodesKnown(): Boolean = contentErrors.none { it.code == "UNKNOWN_INGREDIENT" }

    @AssertTrue(message = "ingredients 의 inclusion_percent 는 0~100 이어야 합니다")
    fun isInclusionPercentValid(): Boolean = contentErrors.none { it.code == "INCLUSION_PERCENT_RANGE" }

    @AssertTrue(message = "failureKind 는 필수입니다")
    fun isFailureKindPresent(): Boolean = isPassed() || failureKind != null

    @AssertTrue(message = "reason 은 필수입니다")
    fun isReasonPresent(): Boolean = isPassed() || reason?.isNotBlank() == true

    private fun isPassed(): Boolean = passed == true

    companion object {
        const val MAX_DESCRIPTION_LENGTH = FoodContentValidator.MAX_DESCRIPTION_LENGTH
    }
}
