package com.meogo.infra.persistence.avoidance

import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "ingredient_avoidance_substance")
class IngredientAvoidanceSubstanceJpaEntity(
    @Column(name = "ingredient_id", nullable = false)
    var ingredientId: Long = 0,

    @Column(name = "substance_id", nullable = false)
    var substanceId: Long = 0,
) : BaseEntity()
