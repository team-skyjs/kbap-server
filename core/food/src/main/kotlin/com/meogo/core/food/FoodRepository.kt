package com.meogo.core.food

interface FoodRepository {
    fun findByKoreanName(name: String): Food?
}
