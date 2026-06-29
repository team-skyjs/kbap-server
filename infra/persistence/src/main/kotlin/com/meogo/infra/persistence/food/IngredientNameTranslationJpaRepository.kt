package com.meogo.infra.persistence.food

import org.springframework.data.jpa.repository.JpaRepository

interface IngredientNameTranslationJpaRepository : JpaRepository<IngredientNameTranslationJpaEntity, Long> {
    fun findByIngredientIdInAndLangCode(
        ingredientIds: Collection<Long>,
        langCode: String,
    ): List<IngredientNameTranslationJpaEntity>
}
