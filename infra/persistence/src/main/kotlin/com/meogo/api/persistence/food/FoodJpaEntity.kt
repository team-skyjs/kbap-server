package com.meogo.api.persistence.food

import com.meogo.api.food.Food
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

    @Column(name = "brief_description", nullable = false, length = 255)
    var briefDescription: String = "",

    @Column(name = "detailed_description", nullable = false, length = 1024)
    var detailedDescription: String = "",

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "food_id", nullable = false)
    var foodIngredients: MutableSet<FoodIngredientJpaEntity> = mutableSetOf(),
) : BaseEntity() {
    fun toDomain(): Food =
        Food.reconstitute(
            id = id,
            koreanName = koreanName,
            imageRef = imageRef,
            briefDescription = briefDescription,
            detailedDescription = detailedDescription,
            ingredients = foodIngredients.map { it.toDomain() },
        )
}
