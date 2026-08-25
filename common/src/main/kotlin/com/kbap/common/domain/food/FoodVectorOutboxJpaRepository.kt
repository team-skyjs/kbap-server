package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodVectorOutboxJpaRepository : JpaRepository<FoodVectorOutbox, Long> {
    fun enqueueIfAbsent(foodId: Long, operation: FoodVectorOutboxOperation) {
        val alreadyPending =
            existsByFoodIdAndOperationAndOutboxStatus(foodId, operation, FoodVectorOutboxStatus.PENDING)
        if (!alreadyPending) {
            save(
                when (operation) {
                    FoodVectorOutboxOperation.UPSERT -> FoodVectorOutbox.upsert(foodId)
                    FoodVectorOutboxOperation.DELETE -> FoodVectorOutbox.delete(foodId)
                },
            )
        }
    }

    fun existsByFoodIdAndOperationAndOutboxStatus(
        foodId: Long,
        operation: FoodVectorOutboxOperation,
        outboxStatus: FoodVectorOutboxStatus,
    ): Boolean

    fun countByOutboxStatus(outboxStatus: FoodVectorOutboxStatus): Long

    fun findTop20ByOutboxStatusOrderByIdDesc(outboxStatus: FoodVectorOutboxStatus): List<FoodVectorOutbox>

    fun findTop5ByFoodIdOrderByIdDesc(foodId: Long): List<FoodVectorOutbox>

    fun findByFoodIdInOrderByIdDesc(foodIds: Collection<Long>): List<FoodVectorOutbox>

    fun findByOutboxStatus(outboxStatus: FoodVectorOutboxStatus, pageable: Pageable): Page<FoodVectorOutbox>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update FoodVectorOutbox v
        set v.outboxStatus = com.kbap.common.domain.food.model.FoodVectorOutboxStatus.PENDING,
            v.attempts = 0,
            v.lastError = null
        where v.outboxStatus = com.kbap.common.domain.food.model.FoodVectorOutboxStatus.FAILED
        """,
    )
    fun retryAllFailed(): Int

    @Query(
        value = """
            SELECT *
            FROM food_vector_outbox
            WHERE id > :afterId
              AND outbox_status = 'PENDING'
              AND status = 'ACTIVE'
            ORDER BY id ASC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findPendingAfterId(
        @Param("afterId") afterId: Long,
        @Param("limit") limit: Int,
    ): List<FoodVectorOutbox>
}
