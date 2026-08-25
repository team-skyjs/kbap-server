package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.util.KoreanMenuNameNormalizer

data class FoodContentCandidate(
    val koreanName: String? = null,
    val description: String?,
    val longDescription: String?,
    val spiciness: Int?,
    val nameTranslations: Map<String, String>?,
    val descriptionTranslations: Map<String, String>?,
    val ingredients: List<FoodIngredient>?,
)

object FoodContentValidator {
    const val MAX_DESCRIPTION_LENGTH = 255
    val SPICINESS_RANGE = 0..10
    val INCLUSION_PERCENT_RANGE = 1..100

    private val targetLangCodes: List<String> = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
    private val knownIngredientCodes: Set<String> = IngredientCode.entries.map { it.name }.toSet()

    fun validate(candidate: FoodContentCandidate, requireComplete: Boolean = true): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        candidate.koreanName?.let {
            if (KoreanMenuNameNormalizer.matchKey(it).isEmpty()) {
                errors += FieldError("koreanName", "NAME_BLANK", "이름은 한글 정규화 후 비어 있을 수 없습니다")
            }
        }
        val descriptionMissing = candidate.description.isNullOrBlank() && requireComplete
        if (descriptionMissing || (candidate.description?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
            errors += FieldError("description", "DESCRIPTION_LENGTH", "description 은 1~${MAX_DESCRIPTION_LENGTH}자여야 합니다")
        }
        if ((candidate.longDescription?.length ?: 0) > Food.MAX_LONG_DESCRIPTION_LENGTH) {
            errors += FieldError("longDescription", "LONG_DESCRIPTION_LENGTH", "longDescription 은 ${Food.MAX_LONG_DESCRIPTION_LENGTH}자 이하여야 합니다")
        }
        val spicinessRange = if (requireComplete) SPICINESS_RANGE else Food.SPICINESS_UNASSESSED..SPICINESS_RANGE.last
        if (candidate.spiciness == null || candidate.spiciness !in spicinessRange) {
            errors += FieldError("spiciness", "SPICINESS_RANGE", "spiciness 는 ${spicinessRange.first}~${spicinessRange.last} 이어야 합니다")
        }
        if (requireComplete) {
            errors += missingTranslations("nameTranslations", candidate.nameTranslations)
            errors += missingTranslations("descriptionTranslations", candidate.descriptionTranslations)
        }
        when (val ingredients = candidate.ingredients) {
            null -> if (requireComplete) {
                errors += FieldError("ingredients", "INGREDIENTS_MISSING", "ingredients 는 필수입니다(해당 없음은 빈 배열)")
            }
            else -> ingredients.forEachIndexed { index, ingredient ->
                if (ingredient.code !in knownIngredientCodes) {
                    errors += FieldError("ingredients[$index].code", "UNKNOWN_INGREDIENT", "ingredients 의 code 는 성분 카탈로그 코드여야 합니다")
                }
                if (ingredient.inclusionPercent !in INCLUSION_PERCENT_RANGE) {
                    errors += FieldError("ingredients[$index].inclusionPercent", "INCLUSION_PERCENT_RANGE", "ingredients 의 inclusion_percent 는 1~100 이어야 합니다")
                }
            }
        }
        return errors
    }

    private fun missingTranslations(field: String, translations: Map<String, String>?): List<FieldError> =
        targetLangCodes
            .filter { translations?.get(it).isNullOrBlank() }
            .map { FieldError("$field.$it", "TRANSLATION_MISSING", "$field 는 9개 대상 언어를 모두 채워야 합니다") }
}
