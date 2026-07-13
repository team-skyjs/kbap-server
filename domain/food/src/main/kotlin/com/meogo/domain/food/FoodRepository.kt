package com.meogo.domain.food

import com.meogo.core.lang.LanguageCode

interface FoodRepository {
    fun findById(id: Long): Food?

    fun findFoodPage(cursor: Long?, size: Int): List<Food>

    fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food>

    fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food>

    fun createIncomplete(koreanNames: Set<String>): Map<String, Food>

    fun findRandomReady(size: Int): List<Food>

    fun findAllReadyByIds(ids: List<Long>): List<Food>
}
