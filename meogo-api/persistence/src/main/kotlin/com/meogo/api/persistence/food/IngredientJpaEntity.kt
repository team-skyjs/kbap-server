package com.meogo.api.persistence.food

import com.meogo.api.food.Ingredient
import com.meogo.api.food.LanguageCode
import com.meogo.api.persistence.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(name = "ingredient")
class IngredientJpaEntity(
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

    @Column(name = "icon_ref", length = 500)
    var iconRef: String? = null,

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "ingredient_id", nullable = false)
    var translations: MutableSet<IngredientNameTranslationJpaEntity> = mutableSetOf(),
) : BaseEntity() {
    fun toDomain(): Ingredient =
        Ingredient(
            id = id,
            koreanName = koreanName,
            names = translations.associate { LanguageCode.from(it.langCode) to it.name },
            iconRef = iconRef,
        )

    companion object {
        fun from(ingredient: Ingredient): IngredientJpaEntity =
            IngredientJpaEntity(
                koreanName = ingredient.koreanName,
                iconRef = ingredient.iconRef,
                translations = ingredient.names
                    .map { (lang, name) -> IngredientNameTranslationJpaEntity(langCode = lang.code, name = name) }
                    .toMutableSet(),
            )
    }
}
