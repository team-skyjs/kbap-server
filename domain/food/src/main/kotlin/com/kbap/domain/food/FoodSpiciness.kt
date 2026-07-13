package com.kbap.domain.food

data class FoodSpiciness(
    val value: Int,
) {
    init {
        require(value in MIN_LEVEL..MAX_LEVEL) { "food.spiciness 는 $MIN_LEVEL..$MAX_LEVEL 범위여야 합니다" }
    }

    companion object {
        const val MIN_LEVEL = 0
        const val MAX_LEVEL = 10
    }
}
