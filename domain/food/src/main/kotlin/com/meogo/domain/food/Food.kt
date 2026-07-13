package com.meogo.domain.food

import com.meogo.core.lang.LanguageCode
import com.meogo.core.lang.LocalizedText
import com.meogo.core.menu.KoreanMenuNameNormalizer
import com.meogo.core.risk.RiskLevel
import com.meogo.core.stereotype.AggregateRoot

@AggregateRoot
class Food private constructor(
    val id: Long?,
    val content: FoodContent,
    val imageRef: String?,
    val spiciness: FoodSpiciness,
    val avoidanceSubstances: List<FoodAvoidanceSubstance>,
    val contentStatus: FoodContentStatus,
) {
    init {
        val codes = avoidanceSubstances.map { it.substanceCode }
        require(codes.size == codes.toSet().size) {
            "food.avoidanceSubstances 에 중복된 기피 성분 코드가 있을 수 없습니다"
        }
    }

    fun isReady(): Boolean = contentStatus == FoodContentStatus.READY

    fun koreanName(): String = content.koreanName()

    fun displayName(lang: LanguageCode): String = content.resolveName(lang)

    fun description(lang: LanguageCode): String = content.resolveDescription(lang)

    fun avoidanceSubstancesByProbability(): List<FoodAvoidanceSubstance> =
        avoidanceSubstances.sortedByDescending { it.inclusionProbability }

    fun overallRisk(avoidedCodes: Set<AvoidanceSubstanceCodeRef>): RiskLevel {
        if (!isReady()) return RiskLevel.UNKNOWN
        val targeted = avoidanceSubstances.filter { it.substanceCode in avoidedCodes }
        return RiskLevel.aggregate(targeted.map { it.riskLevel() })
    }

    companion object {
        const val PLACEHOLDER_DESCRIPTION = "설명 준비 중"

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
            contentStatus = FoodContentStatus.READY,
        )

        fun incomplete(koreanName: String): Food {
            require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
            require(koreanName.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH) {
                "food.koreanName 은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
            }
            return Food(
                id = null,
                content = FoodContent(
                    name = LocalizedText(korean = koreanName),
                    description = LocalizedText(korean = PLACEHOLDER_DESCRIPTION),
                ),
                imageRef = null,
                spiciness = FoodSpiciness(FoodSpiciness.MIN_LEVEL),
                avoidanceSubstances = emptyList(),
                contentStatus = FoodContentStatus.INCOMPLETE,
            )
        }

        fun reconstitute(
            id: Long,
            content: FoodContent,
            imageRef: String?,
            spiciness: FoodSpiciness,
            avoidanceSubstances: List<FoodAvoidanceSubstance>,
            contentStatus: FoodContentStatus = FoodContentStatus.READY,
        ): Food = Food(
            id = id,
            content = content,
            imageRef = imageRef,
            spiciness = spiciness,
            avoidanceSubstances = avoidanceSubstances,
            contentStatus = contentStatus,
        )
    }
}
