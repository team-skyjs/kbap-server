package com.meogo.core.food

interface FoodRepository {
    fun findById(id: Long): Food?

    fun findMenuPage(cursor: Long?, size: Int): List<Food>

    fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food>

    fun createIncomplete(koreanName: String): Food
}
