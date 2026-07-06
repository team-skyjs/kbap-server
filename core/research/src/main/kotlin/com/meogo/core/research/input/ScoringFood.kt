package com.meogo.core.research.input

data class ScoringFood(
    val foodId: Long,
    val koreanName: String,
) {
    init {
        require(koreanName.isNotBlank()) { "research.scoringFood.koreanName 은 blank 일 수 없습니다" }
    }
}
