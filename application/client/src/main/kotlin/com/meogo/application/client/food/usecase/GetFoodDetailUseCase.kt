package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.application.client.food.dto.GetFoodDetailResult
import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.meogo.domain.avoidance.AvoidanceSubstanceService
import com.meogo.domain.food.AvoidanceSubstanceCodeRef
import com.meogo.domain.food.FoodErrorCode
import com.meogo.domain.food.FoodException
import com.meogo.domain.food.FoodService
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
