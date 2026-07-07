package com.meogo.infra.persistence.food

import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "food_avoidance_substance",
    uniqueConstraints = [UniqueConstraint(columnNames = ["food_id", "substance_code"])],
)
class FoodAvoidanceSubstanceJpaEntity(
    @Column(name = "substance_code", nullable = false, length = 40)
    var substanceCode: String = "",

    @Column(name = "inclusion_percent", nullable = false)
    var inclusionPercent: Int = 0,
) : BaseEntity() {
    fun toDomain(): FoodAvoidanceSubstance =
        FoodAvoidanceSubstance(
            substanceCode = AvoidanceSubstanceCodeRef(substanceCode),
            inclusionProbability = inclusionPercent,
        )

    companion object {
        fun from(domain: FoodAvoidanceSubstance): FoodAvoidanceSubstanceJpaEntity =
            FoodAvoidanceSubstanceJpaEntity(
                substanceCode = domain.substanceCode.value,
                inclusionPercent = domain.inclusionProbability,
            )
    }
}
