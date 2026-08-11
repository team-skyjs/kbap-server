package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class AdminFoodContentIngestRequest(
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
    @AssertTrue(message = "description 은 1~255자여야 합니다")
    fun isDescriptionValid(): Boolean =
        !isPassed() || description?.isNotBlank() == true && description.length <= MAX_DESCRIPTION_LENGTH

    @AssertTrue(message = "longDescription 은 1000자 이하여야 합니다")
    fun isLongDescriptionValid(): Boolean =
        longDescription == null || longDescription.length <= Food.MAX_LONG_DESCRIPTION_LENGTH

    @AssertTrue(message = "spiciness 는 0~10 이어야 합니다")
    fun isSpicinessValid(): Boolean = !isPassed() || spiciness in SPICINESS_RANGE

    @AssertTrue(message = "nameTranslations 는 9개 대상 언어를 모두 채워야 합니다")
    fun isNameTranslationsValid(): Boolean = !isPassed() || hasAllTargetLangs(nameTranslations)

    @AssertTrue(message = "descriptionTranslations 는 9개 대상 언어를 모두 채워야 합니다")
    fun isDescriptionTranslationsValid(): Boolean = !isPassed() || hasAllTargetLangs(descriptionTranslations)

    // 빈 배열(조사 완료·해당 없음)과 누락(미조사)은 위험도 계산이 갈리므로 누락을 통과시키지 않는다.
    @AssertTrue(message = "ingredients 는 필수입니다(해당 없음은 빈 배열)")
    fun isIngredientsPresent(): Boolean = !isPassed() || ingredients != null

    // 카탈로그에 없는 코드가 저장되면 음식 상세 조회가 코드 변환에서 터진다.
    @AssertTrue(message = "ingredients 의 code 는 성분 카탈로그 코드여야 합니다")
    fun isIngredientCodesKnown(): Boolean =
        !isPassed() || ingredients.orEmpty().all { it.code in KNOWN_INGREDIENT_CODES }

    @AssertTrue(message = "ingredients 의 inclusion_percent 는 0~100 이어야 합니다")
    fun isInclusionPercentValid(): Boolean =
        !isPassed() || ingredients.orEmpty().all { it.inclusionPercent in INCLUSION_PERCENT_RANGE }

    @AssertTrue(message = "failureKind 는 필수입니다")
    fun isFailureKindPresent(): Boolean = isPassed() || failureKind != null

    @AssertTrue(message = "reason 은 필수입니다")
    fun isReasonPresent(): Boolean = isPassed() || reason?.isNotBlank() == true

    private fun isPassed(): Boolean = passed == true

    private fun hasAllTargetLangs(translations: Map<String, String>?): Boolean =
        translations != null && TARGET_LANG_CODES.all { translations[it]?.isNotBlank() == true }

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 255

        private val SPICINESS_RANGE = 0..10

        private val INCLUSION_PERCENT_RANGE = 0..100

        private val TARGET_LANG_CODES: Set<String> =
            LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }.toSet()

        private val KNOWN_INGREDIENT_CODES: Set<String> = IngredientCode.entries.map { it.name }.toSet()
    }
}
