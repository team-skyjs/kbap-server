package com.meogo.core.food

import com.meogo.core.kernel.lang.LocalizedText

data class FoodContent(
    val name: LocalizedText,
    val description: LocalizedText,
) {
    init {
        require(name.korean.length <= MAX_NAME_LENGTH) { "food.name 은 ${MAX_NAME_LENGTH}자를 초과할 수 없습니다" }
        require(description.korean.length <= MAX_DESCRIPTION_LENGTH) { "food.description 은 ${MAX_DESCRIPTION_LENGTH}자를 초과할 수 없습니다" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 255
        const val MAX_DESCRIPTION_LENGTH = 255
    }
}
