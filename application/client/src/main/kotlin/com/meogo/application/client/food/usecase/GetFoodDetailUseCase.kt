package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.application.client.food.dto.GetFoodDetailResult
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.risk.RiskLevel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetFoodDetailUseCase(
    private val foodRepository: FoodRepository,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
    private val languageResolver: LanguageResolver,
    private val mockAvoidanceRiskMarker: MockAvoidanceRiskMarker,
) {
    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = languageResolver.resolve(input.lang)
        val food = foodRepository.findByKoreanName(input.menuName.trim())
            ?: throw FoodException(FoodErrorCode.NOT_FOUND)

        val orderedSubstances = food.avoidanceSubstancesByProbability()
        val codedSubstances = orderedSubstances.map { it to AvoidanceSubstanceCode.valueOf(it.substanceCode.value) }
        val catalog = avoidanceSubstanceRepository.findByCodes(codedSubstances.map { it.second }.toSet())
            .associateBy { it.code }
        val risks = mockAvoidanceRiskMarker.mark(orderedSubstances.map { it.substanceCode.value })

        val foodName = food.content.name(lang)
        val description = food.content.description(lang)

        val avoidanceSubstances = codedSubstances.map { (substance, code) ->
            val catalogEntry = catalog[code]
                ?: throw IllegalStateException("avoidance substance catalog missing for code: $code")
            GetFoodDetailResult.AvoidanceSubstanceView(
                name = catalogEntry.displayName(lang),
                iconRef = null,
                inclusionProbability = substance.inclusionProbability,
                riskStatus = risks[substance.substanceCode.value] ?: RiskLevel.SAFE,
            )
        }

        return GetFoodDetailResult(
            name = foodName,
            imageRef = food.imageRef,
            description = description,
            spiciness = food.spiciness.value,
            avoidanceSubstances = avoidanceSubstances,
        )
    }
}
