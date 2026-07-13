package com.kbap.domain.food

import com.kbap.core.id.FoodId
import com.kbap.core.persistence.BaseEntity
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
    @Column(name = "food_id", nullable = false)
    var foodId: FoodId = FoodId(0),

    @Column(name = "substance_code", nullable = false, length = 40)
    var substanceCode: String = "",

    @Column(name = "inclusion_percent", nullable = false)
    var inclusionPercent: Int = 0,
) : BaseEntity() {
    internal constructor() : this(FoodId(0), "", 0)

    fun toDomain(): FoodAvoidanceSubstance =
        FoodAvoidanceSubstance(
            substanceCode = AvoidanceSubstanceCodeRef(substanceCode),
            inclusionProbability = inclusionPercent,
        )
}
