package com.meogo.domain.research.ensemble

data class FoodInclusionScore(
    val foodId: Long,
    val substanceCode: String,
    val inclusionConfidence: Int,
    val avgScore: Double,
    val avgProbability: Double,
    val agreementFactor: Double,
)
