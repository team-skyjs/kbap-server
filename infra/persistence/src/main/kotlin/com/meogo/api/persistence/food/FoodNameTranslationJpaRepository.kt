package com.meogo.api.persistence.food

import org.springframework.data.jpa.repository.JpaRepository

interface FoodNameTranslationJpaRepository : JpaRepository<FoodNameTranslationJpaEntity, Long> {
    fun findByFoodIdAndLangCode(foodId: Long, langCode: String): FoodNameTranslationJpaEntity?
}
