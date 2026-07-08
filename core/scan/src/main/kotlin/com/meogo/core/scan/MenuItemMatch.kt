package com.meogo.core.scan

sealed interface MenuItemMatch {
    data class Matched(val foodId: Long) : MenuItemMatch {
        init {
            require(foodId > 0) { "Matched.foodId 는 양수여야 합니다" }
        }
    }

    data object Pending : MenuItemMatch

    data object NotFood : MenuItemMatch
}
