package com.meogo.core.food

interface FoodRepository {
    fun findById(id: Long): Food?

    fun findMenuPage(cursor: Long?, size: Int): List<Food>

    fun findFoodIdByKoreanMatchKey(key: String): Long?
}
