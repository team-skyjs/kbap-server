package com.meogo.core.food

data class FoodAvoidanceSubstance(
    val substanceCode: AvoidanceSubstanceCodeRef,
    val inclusionProbability: Int,
) {
    init {
        require(inclusionProbability in MIN_PROBABILITY..MAX_PROBABILITY) {
            "foodAvoidanceSubstance.inclusionProbability 는 $MIN_PROBABILITY..$MAX_PROBABILITY 범위여야 합니다"
        }
    }

    companion object {
        const val MIN_PROBABILITY = 1
        const val MAX_PROBABILITY = 100
    }
}
