package com.meogo.domain.food

import com.meogo.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "food_avoidance_substance",
    uniqueConstraints = [UniqueConstraint(columnNames = ["food_id", "substance_code"])],
)
internal class FoodAvoidanceSubstanceJpaEntity(
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
}
