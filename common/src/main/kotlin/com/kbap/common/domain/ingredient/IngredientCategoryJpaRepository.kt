package com.kbap.common.domain.ingredient

import com.kbap.common.domain.ingredient.model.IngredientCategory
import org.springframework.data.jpa.repository.JpaRepository

interface IngredientCategoryJpaRepository : JpaRepository<IngredientCategory, Long>
