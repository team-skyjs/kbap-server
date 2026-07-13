package com.meogo.domain.research.parse

data class SubstanceJudgement(
    val code: String,
    val score: Int,
    val probability: Int,
) {
    init {
        require(score in 0..2) { "research.substanceJudgement.score 는 0..2 범위여야 합니다" }
        require(probability in 1..100) { "research.substanceJudgement.probability 는 1..100 범위여야 합니다" }
    }
}
