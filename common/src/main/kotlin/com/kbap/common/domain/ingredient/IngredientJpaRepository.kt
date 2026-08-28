package com.kbap.common.domain.ingredient

import com.kbap.common.domain.ingredient.model.Ingredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import org.springframework.data.jpa.repository.JpaRepository

interface IngredientJpaRepository : JpaRepository<Ingredient, Long> {
    fun findByCodeIn(codes: Set<IngredientCode>): List<Ingredient>

    fun findAllByOrderByCode(): List<Ingredient>
}
