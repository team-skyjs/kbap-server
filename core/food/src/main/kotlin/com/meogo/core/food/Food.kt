package com.meogo.core.food

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Food private constructor(
    val id: Long?,
    val content: FoodContent,
    val imageRef: String?,
    val spiciness: FoodSpiciness,
    val avoidanceSubstances: List<FoodAvoidanceSubstance>,
) {
    init {
        val codes = avoidanceSubstances.map { it.substanceCode }
        require(codes.size == codes.toSet().size) {
            "food.avoidanceSubstances 에 중복된 기피 성분 코드가 있을 수 없습니다"
        }
    }

    fun displayName(lang: LanguageCode): String = content.resolveName(lang)

    fun description(lang: LanguageCode): String = content.resolveDescription(lang)

    fun avoidanceSubstancesByProbability(): List<FoodAvoidanceSubstance> =
        avoidanceSubstances.sortedByDescending { it.inclusionProbability }

    fun overallRisk(avoidedCodes: Set<AvoidanceSubstanceCodeRef>): RiskLevel {
        val targeted = avoidanceSubstances.filter { it.substanceCode in avoidedCodes }
        return RiskLevel.aggregate(targeted.map { it.riskLevel() })
    }

    companion object {
        fun create(
            content: FoodContent,
            imageRef: String? = null,
            spiciness: FoodSpiciness,
            avoidanceSubstances: List<FoodAvoidanceSubstance>,
        ): Food = Food(
            id = null,
            content = content,
            imageRef = imageRef,
            spiciness = spiciness,
            avoidanceSubstances = avoidanceSubstances,
        )

        fun reconstitute(
            id: Long,
            content: FoodContent,
            imageRef: String?,
            spiciness: FoodSpiciness,
            avoidanceSubstances: List<FoodAvoidanceSubstance>,
        ): Food = Food(
            id = id,
            content = content,
            imageRef = imageRef,
            spiciness = spiciness,
            avoidanceSubstances = avoidanceSubstances,
        )
    }
}
