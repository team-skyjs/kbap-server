package com.meogo.app.batch.promotion

import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.research.candidate.FoodCandidate

class CandidatePromotionMapper {

    fun toFood(candidate: FoodCandidate): Food =
        Food.create(
            content = FoodContent(
                name = LocalizedText(korean = candidate.koreanName, translations = emptyMap()),
                description = LocalizedText(
                    korean = candidate.koreanDescription!!,
                    translations = candidate.descriptionTranslations,
                ),
            ),
            imageRef = null,
            spiciness = FoodSpiciness(INITIAL_SPICINESS),
            avoidanceSubstances = candidate.substanceMapping.map { snapshot ->
                FoodAvoidanceSubstance(
                    substanceCode = AvoidanceSubstanceCodeRef(snapshot.code),
                    inclusionProbability = snapshot.inclusionPercent,
                )
            },
        )

    companion object {
        const val INITIAL_SPICINESS = 0
    }
}
