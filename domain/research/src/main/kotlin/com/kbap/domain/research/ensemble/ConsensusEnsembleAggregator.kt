package com.kbap.domain.research.ensemble

import com.kbap.domain.research.input.CandidateSubstance
import com.kbap.domain.research.input.ScoringFood
import com.kbap.domain.research.parse.ModelScoring
import kotlin.math.roundToInt

class ConsensusEnsembleAggregator(
    private val policy: EnsemblePolicy = EnsemblePolicy.DEFAULT,
) {
    private val contentSelector = FoodContentSelector()

    fun aggregate(
        foods: List<ScoringFood>,
        candidates: List<CandidateSubstance>,
        perModel: List<ModelScoring>,
    ): List<FoodScoringResult> {
        require(perModel.size == MODEL_COUNT) {
            "research.consensusEnsemble.perModel 은 정확히 ${MODEL_COUNT}개여야 합니다"
        }
        return foods.map { food ->
            val scores = candidates.map { candidate -> score(food.foodId, candidate, perModel) }
            val content = contentSelector.select(food.foodId, perModel)
            FoodScoringResult(
                foodId = food.foodId,
                status = FoodScoringStatus.SCORED,
                scores = scores,
                nameTranslations = content.nameTranslations,
                description = content.description,
            )
        }
    }

    private fun score(
        foodId: Long,
        candidate: CandidateSubstance,
        perModel: List<ModelScoring>,
    ): FoodInclusionScore {
        val judgements = perModel.map { model ->
            model.included[foodId]?.firstOrNull { it.code == candidate.code }
        }
        val scoreValues = judgements.map { it?.score ?: MISSING_SCORE }
        val probabilityValues = judgements.map { it?.probability ?: MISSING_PROBABILITY }

        val avgScore = scoreValues.average()
        val avgProbability = probabilityValues.average()
        val base = policy.scoreWeight * (avgScore / MAX_SCORE) +
            (1.0 - policy.scoreWeight) * (avgProbability / MAX_PROBABILITY)
        val agreementFactor = agreementFactor(scoreValues.distinct().size)
        val inclusionConfidence = (base * agreementFactor * MAX_PROBABILITY)
            .roundToInt()
            .coerceIn(policy.floor, MAX_PROBABILITY)

        return FoodInclusionScore(
            foodId = foodId,
            substanceCode = candidate.code,
            inclusionConfidence = inclusionConfidence,
            avgScore = avgScore,
            avgProbability = avgProbability,
            agreementFactor = agreementFactor,
        )
    }

    private fun agreementFactor(distinctScoreCount: Int): Double =
        when (distinctScoreCount) {
            1 -> 1.0
            2 -> 0.9
            else -> 0.75
        }

    companion object {
        private const val MODEL_COUNT = 3
        private const val MISSING_SCORE = 0
        private const val MISSING_PROBABILITY = 1
        private const val MAX_SCORE = 2.0
        private const val MAX_PROBABILITY = 100
    }
}
