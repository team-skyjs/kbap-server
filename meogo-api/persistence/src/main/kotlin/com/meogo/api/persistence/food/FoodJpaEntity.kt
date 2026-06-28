package com.meogo.api.persistence.food

import com.meogo.api.food.Food
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
@Table(name = "food")
class FoodJpaEntity(
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

    @Column(name = "image_ref", length = 500)
    var imageRef: String? = null,

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "food_id", nullable = false)
    var translations: MutableSet<FoodNameTranslationJpaEntity> = mutableSetOf(),

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "food_id", nullable = false)
    var foodIngredients: MutableSet<FoodIngredientJpaEntity> = mutableSetOf(),
) : BaseEntity() {
    fun toDomain(): Food =
        Food(
            id = id,
            koreanName = koreanName,
            names = translations.associate { LanguageCode.from(it.langCode) to it.name },
            imageRef = imageRef,
            ingredients = foodIngredients.sortedBy { it.displayOrder }.map { it.toDomain() },
        )

    companion object {
        fun from(food: Food): FoodJpaEntity =
            FoodJpaEntity(
                koreanName = food.koreanName,
                imageRef = food.imageRef,
                translations = food.names
                    .map { (lang, name) -> FoodNameTranslationJpaEntity(langCode = lang.code, name = name) }
                    .toMutableSet(),
                foodIngredients = food.ingredients
                    .map { FoodIngredientJpaEntity.from(it) }
                    .toMutableSet(),
            )
    }
}
