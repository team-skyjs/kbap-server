package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "food_view_log",
    indexes = [Index(name = "idx_food_view_log_created_food", columnList = "created_at, food_id")],
)
class FoodViewLog(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Column(name = "member_id")
    var memberId: Long? = null,
) : BaseEntity()
