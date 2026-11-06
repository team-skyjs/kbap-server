package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "food_processed_link")
class FoodProcessedLink(
    @Column(name = "food_id", nullable = false)
    val foodId: Long = 0,

    @Column(name = "processed_food_product_id", nullable = false)
    val processedFoodProductId: Long = 0,

    @Column(name = "linked_by")
    val linkedBy: Long? = null,
) : BaseEntity()
