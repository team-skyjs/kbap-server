package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "food_image",
    indexes = [Index(name = "idx_food_image_food", columnList = "food_id, status")],
)
class FoodImage(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Column(name = "image_key", nullable = false, length = 500)
    var imageKey: String = "",

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, columnDefinition = "ENUM('GENERATED')")
    var source: FoodImageSource = FoodImageSource.GENERATED,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    companion object {
        fun primary(foodId: Long, imageKey: String): FoodImage =
            FoodImage(foodId = foodId, imageKey = imageKey, isPrimary = true)
    }
}
