package com.meogo.api.food

interface FoodRepository {
    fun findByKoreanName(name: String): Food?
}
