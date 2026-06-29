package com.meogo.core.food

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Ingredient private constructor(
    val id: Long?,
    val koreanName: String,
    val iconRef: String?,
) {
    init {
        require(koreanName.isNotBlank()) { "ingredient.koreanName 은 blank 일 수 없습니다" }
    }

    companion object {
        fun create(
            koreanName: String,
            iconRef: String? = null,
        ): Ingredient = Ingredient(id = null, koreanName = koreanName, iconRef = iconRef)

        fun reconstitute(
            id: Long,
            koreanName: String,
            iconRef: String?,
        ): Ingredient = Ingredient(id = id, koreanName = koreanName, iconRef = iconRef)
    }
}
