package com.meogo.core.food

data class FoodContent(
    val koreanName: String,
    val description: String,
) {
    init {
        require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
        require(koreanName.length <= MAX_NAME_LENGTH) { "food.koreanName 은 ${MAX_NAME_LENGTH}자를 초과할 수 없습니다" }
        require(description.isNotBlank()) { "food.description 은 blank 일 수 없습니다" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "food.description 은 ${MAX_DESCRIPTION_LENGTH}자를 초과할 수 없습니다" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 255
        const val MAX_DESCRIPTION_LENGTH = 255
    }
}
