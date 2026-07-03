package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodSpiciness
import com.meogo.infra.persistence.BaseEntity
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

    @Column(name = "description", nullable = false, length = 255)
    var description: String = "",

    @Column(name = "spiciness", nullable = false)
    var spiciness: Int = 0,

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "food_id", nullable = false)
    var foodAvoidanceSubstances: MutableSet<FoodAvoidanceSubstanceJpaEntity> = mutableSetOf(),
) : BaseEntity() {
    fun toDomain(): Food =
        Food.reconstitute(
            id = id,
            content = FoodContent(
                koreanName = koreanName,
                description = description,
            ),
            imageRef = imageRef,
            spiciness = FoodSpiciness(spiciness),
            avoidanceSubstances = foodAvoidanceSubstances.map { it.toDomain() },
        )
}
