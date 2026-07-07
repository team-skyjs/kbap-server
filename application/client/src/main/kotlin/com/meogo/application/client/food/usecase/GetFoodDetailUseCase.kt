package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.application.client.food.dto.GetFoodDetailResult
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException
import com.meogo.core.food.FoodRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetFoodDetailUseCase(
    private val foodRepository: FoodRepository,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
    private val languageResolver: LanguageResolver,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = languageResolver.resolve(input.lang)
        val food = foodRepository.findById(input.foodId)
            ?: throw FoodException(FoodErrorCode.NOT_FOUND)

        val orderedSubstances = food.avoidanceSubstancesByProbability()
        val codedSubstances = orderedSubstances.map { it to AvoidanceSubstanceCode.valueOf(it.substanceCode.value) }
        val catalog = avoidanceSubstanceRepository.findByCodes(codedSubstances.map { it.second }.toSet())
            .associateBy { it.code }

        val foodName = food.displayName(lang)
        val description = food.description(lang)

        val (resolvable, missing) = codedSubstances.partition { (_, code) -> code in catalog }

        missing.forEach { (_, code) ->
            log.warn(
                "avoidance substance skipped (catalog missing / soft-deleted): foodId={} substanceCode={}",
                food.id,
                code,
            )
        }

        val avoidanceSubstances = resolvable.map { (substance, code) ->
            val catalogEntry = catalog.getValue(code)
            GetFoodDetailResult.AvoidanceSubstanceView(
                name = catalogEntry.displayName(lang),
                iconRef = null,
                inclusionProbability = substance.inclusionProbability,
                riskStatus = substance.riskLevel(),
            )
        }

        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes().map { AvoidanceSubstanceCodeRef(it.name) }.toSet()
        val resolvableCodes = resolvable.map { it.first.substanceCode }.toSet()
        val overallRiskStatus = food.overallRisk(avoidedCodes intersect resolvableCodes)

        return GetFoodDetailResult(
            name = foodName,
            koreanName = food.koreanName().takeIf { it != foodName },
            imageRef = food.imageRef,
            description = description,
            spiciness = food.spiciness.value,
            overallRiskStatus = overallRiskStatus,
            avoidanceSubstances = avoidanceSubstances,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(GetFoodDetailUseCase::class.java)
    }
}
