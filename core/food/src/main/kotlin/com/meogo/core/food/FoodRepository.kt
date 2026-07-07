package com.meogo.core.food

interface FoodRepository {
    fun findByKoreanName(name: String): Food?

    fun findMenuPage(cursor: Long?, size: Int): List<Food>
}
