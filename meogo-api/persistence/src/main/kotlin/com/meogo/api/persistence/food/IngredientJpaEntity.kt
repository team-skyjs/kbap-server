package com.meogo.api.persistence.food

import com.meogo.api.food.Ingredient
import com.meogo.api.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "ingredient")
class IngredientJpaEntity(
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

    @Column(name = "icon_ref", length = 500)
    var iconRef: String? = null,
) : BaseEntity() {
    fun toDomain(): Ingredient =
        Ingredient.reconstitute(
            id = id,
            koreanName = koreanName,
            iconRef = iconRef,
        )

    companion object {
        fun from(ingredient: Ingredient): IngredientJpaEntity =
            IngredientJpaEntity(
                koreanName = ingredient.koreanName,
                iconRef = ingredient.iconRef,
            )
    }
}
