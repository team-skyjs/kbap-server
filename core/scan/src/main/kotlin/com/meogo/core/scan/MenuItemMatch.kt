package com.meogo.core.scan

sealed interface MenuItemMatch {
    data class Matched(val foodId: Long) : MenuItemMatch {
        init {
            require(foodId > 0) { "Matched.foodId 는 양수여야 합니다" }
        }
    }

    data class Unmatched(val foodId: Long? = null) : MenuItemMatch {
        init {
            require(foodId == null || foodId > 0) { "Unmatched.foodId 는 양수여야 합니다" }
        }
    }

    data object NotFood : MenuItemMatch
}
