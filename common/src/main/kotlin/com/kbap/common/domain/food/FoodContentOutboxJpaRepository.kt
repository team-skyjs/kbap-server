package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.springframework.data.jpa.repository.JpaRepository

interface FoodContentOutboxJpaRepository : JpaRepository<FoodContentOutbox, Long> {
    fun existsByFoodIdAndOutboxStatus(foodId: Long, outboxStatus: FoodContentOutboxStatus): Boolean

    fun findByFoodIdInAndOutboxStatus(foodIds: Collection<Long>, outboxStatus: FoodContentOutboxStatus): List<FoodContentOutbox>

    fun findByOutboxStatusOrderByIdAsc(outboxStatus: FoodContentOutboxStatus): List<FoodContentOutbox>
}
