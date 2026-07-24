package com.kbap.core.risk

enum class RiskLevel(private val severity: Int) {
    SAFE(0),
    CAUTION(1),
    DANGER(2),
    UNKNOWN(-1),
    ;

    companion object {
        const val MIN_PROBABILITY = 1
        const val MAX_PROBABILITY = 100
        const val CAUTION_AT_LEAST = 10
        const val DANGER_AT_LEAST = 60

        fun fromInclusionProbability(probability: Int): RiskLevel {
            require(probability in MIN_PROBABILITY..MAX_PROBABILITY) {
                "riskLevel.probability 는 $MIN_PROBABILITY..$MAX_PROBABILITY 범위여야 합니다"
            }
            return when {
                probability < CAUTION_AT_LEAST -> SAFE
                probability < DANGER_AT_LEAST -> CAUTION
                else -> DANGER
            }
        }

        fun aggregate(levels: Collection<RiskLevel>): RiskLevel =
            when {
                levels.isEmpty() -> SAFE
                levels.any { it == UNKNOWN } -> UNKNOWN
                else -> levels.maxBy { it.severity }
            }
    }
}
