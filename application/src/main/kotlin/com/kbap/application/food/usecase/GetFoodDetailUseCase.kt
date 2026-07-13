package com.kbap.application.food.usecase

import com.kbap.application.food.dto.GetFoodDetailInput
import com.kbap.application.food.dto.GetFoodDetailResult
import com.kbap.domain.avoidance.AvoidanceSubstanceCode
import com.kbap.domain.avoidance.AvoidanceSubstanceService
import com.kbap.domain.food.AvoidanceSubstanceCodeRef
import com.kbap.domain.food.FoodErrorCode
import com.kbap.domain.food.FoodException
import com.kbap.domain.food.FoodService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetFoodDetailUseCase(
    private val foodService: FoodService,
    private val avoidanceSubstanceService: AvoidanceSubstanceService,
    private val languageResolver: LanguageResolver,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = languageResolver.resolve(input.lang)
        val food = foodService.findById(input.foodId)
            ?: throw FoodException(FoodErrorCode.NOT_FOUND)

        val orderedSubstances = food.avoidanceSubstancesByProbability()
        val codedSubstances = orderedSubstances.map { it to AvoidanceSubstanceCode.valueOf(it.substanceCode.value) }
        val catalog = avoidanceSubstanceService.findByCodes(codedSubstances.map { it.second }.toSet())
            .associateBy { it.code }

        val foodName = food.displayName(lang)
        val description = food.description(lang)

        val avoidanceSubstances = codedSubstances.map { (substance, code) ->
            GetFoodDetailResult.AvoidanceSubstanceView(
                name = catalog.getValue(code).displayName(lang),
                iconRef = null,
                inclusionProbability = substance.inclusionProbability,
                riskStatus = substance.riskLevel(),
            )
        }

        val userAvoidedCodes = avoidedSubstanceProvider.avoidedCodes(input.memberId).map { AvoidanceSubstanceCodeRef(it.name) }.toSet()

        return GetFoodDetailResult(
            name = foodName,
            koreanName = food.koreanName().takeIf { it != foodName },
            imageRef = food.imageRef,
            description = description,
            spiciness = food.spiciness.value,
            overallRiskStatus = food.overallRisk(userAvoidedCodes),
            avoidanceSubstances = avoidanceSubstances,
        )
    }
}
