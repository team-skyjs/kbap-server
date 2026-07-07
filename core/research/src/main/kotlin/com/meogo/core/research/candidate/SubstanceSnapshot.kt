package com.meogo.core.research.candidate

data class SubstanceSnapshot(
    val code: String,
    val inclusionPercent: Int,
) {
    init {
        require(inclusionPercent in 1..100) {
            "substanceSnapshot.inclusionPercent 는 1..100 범위여야 합니다"
        }
    }
}
