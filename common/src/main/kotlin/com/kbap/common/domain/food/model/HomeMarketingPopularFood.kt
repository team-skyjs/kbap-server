package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "home_marketing_popular_food",
    uniqueConstraints = [UniqueConstraint(name = "uq_home_marketing_popular_food_food_id", columnNames = ["food_id"])],
)
class HomeMarketingPopularFood(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,
) : BaseEntity()
