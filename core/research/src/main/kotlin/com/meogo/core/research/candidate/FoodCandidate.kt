package com.meogo.core.research.candidate

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class FoodCandidate private constructor(
    val id: Long?,
    val koreanName: String,
    val koreanDescription: String?,
    val descriptionTranslations: Map<LanguageCode, String>,
    val substanceMapping: List<SubstanceSnapshot>,
    val publishedFoodId: Long?,
) {
    init {
        require(koreanName.isNotBlank()) {
            "foodCandidate.koreanName 은 blank 일 수 없습니다"
        }
        require(LanguageCode.KO !in descriptionTranslations.keys) {
            "foodCandidate.descriptionTranslations 에 KO 등 원문 언어 키가 포함될 수 없습니다"
        }
    }

    fun isComplete(): Boolean =
        koreanDescription != null &&
            substanceMapping.isNotEmpty() &&
            descriptionTranslations.keys == TARGET_LANGUAGES &&
            publishedFoodId == null

    companion object {
        val TARGET_LANGUAGES: Set<LanguageCode> = LanguageCode.entries.toSet() - LanguageCode.KO

        fun create(
            koreanName: String,
            koreanDescription: String?,
            descriptionTranslations: Map<LanguageCode, String>,
            substanceMapping: List<SubstanceSnapshot>,
        ): FoodCandidate = FoodCandidate(
            id = null,
            koreanName = koreanName,
            koreanDescription = koreanDescription,
            descriptionTranslations = descriptionTranslations,
            substanceMapping = substanceMapping,
            publishedFoodId = null,
        )

        fun reconstitute(
            id: Long,
            koreanName: String,
            koreanDescription: String?,
            descriptionTranslations: Map<LanguageCode, String>,
            substanceMapping: List<SubstanceSnapshot>,
            publishedFoodId: Long?,
        ): FoodCandidate = FoodCandidate(
            id = id,
            koreanName = koreanName,
            koreanDescription = koreanDescription,
            descriptionTranslations = descriptionTranslations,
            substanceMapping = substanceMapping,
            publishedFoodId = publishedFoodId,
        )
    }
}
