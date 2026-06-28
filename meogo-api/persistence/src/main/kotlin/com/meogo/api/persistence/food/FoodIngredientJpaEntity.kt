package com.meogo.api.persistence.food

import com.meogo.api.food.FoodIngredient
import com.meogo.api.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "food_ingredient")
class FoodIngredientJpaEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    var ingredient: IngredientJpaEntity = IngredientJpaEntity(),

    @Column(name = "inclusion_percent", nullable = false)
    var inclusionPercent: Int = 0,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
) : BaseEntity() {
    fun toDomain(): FoodIngredient =
        FoodIngredient(
            id = id,
            ingredient = ingredient.toDomain(),
            inclusionPercent = inclusionPercent,
            displayOrder = displayOrder,
        )
}
