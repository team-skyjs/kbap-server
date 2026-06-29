package com.meogo.infra.persistence.food

import org.springframework.data.jpa.repository.JpaRepository

interface FoodNameTranslationJpaRepository : JpaRepository<FoodNameTranslationJpaEntity, Long> {
    fun findByFoodIdAndLangCode(foodId: Long, langCode: String): FoodNameTranslationJpaEntity?
}
