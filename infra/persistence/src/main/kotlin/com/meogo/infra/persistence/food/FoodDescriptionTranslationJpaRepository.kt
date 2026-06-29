package com.meogo.infra.persistence.food

import org.springframework.data.jpa.repository.JpaRepository

interface FoodDescriptionTranslationJpaRepository : JpaRepository<FoodDescriptionTranslationJpaEntity, Long> {
    fun findByFoodIdAndLangCode(foodId: Long, langCode: String): List<FoodDescriptionTranslationJpaEntity>
}
