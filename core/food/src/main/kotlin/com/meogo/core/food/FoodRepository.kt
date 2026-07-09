package com.meogo.core.food

import com.meogo.core.kernel.lang.LanguageCode

interface FoodRepository {
    fun findById(id: Long): Food?

    fun findMenuPage(cursor: Long?, size: Int): List<Food>

    fun searchMenuPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food>
}
