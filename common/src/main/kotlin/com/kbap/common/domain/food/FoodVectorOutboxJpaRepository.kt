package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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

    fun findByFoodIdAndOperationAndOutboxStatus(
        foodId: Long,
        operation: FoodVectorOutboxOperation,
        outboxStatus: FoodVectorOutboxStatus,
    ): List<FoodVectorOutbox>

    fun countByOutboxStatus(outboxStatus: FoodVectorOutboxStatus): Long

    fun findByOutboxStatus(outboxStatus: FoodVectorOutboxStatus, pageable: Pageable): Page<FoodVectorOutbox>

    @Query(
        """
        select o from FoodVectorOutbox o
        where (:foodId is not null and o.foodId = :foodId)
           or exists (
               select 1 from Food f
               where f.id = o.foodId and f.displayName like concat('%', :keyword, '%') escape '\'
           )
        """,
    )
    fun searchByFoodKeyword(
        @Param("keyword") keyword: String,
        @Param("foodId") foodId: Long?,
        pageable: Pageable,
    ): Page<FoodVectorOutbox>

    @Query(
        """
        select o from FoodVectorOutbox o
        where o.outboxStatus = :outboxStatus
          and (
              (:foodId is not null and o.foodId = :foodId)
              or exists (
                  select 1 from Food f
                  where f.id = o.foodId and f.displayName like concat('%', :keyword, '%') escape '\'
              )
          )
        """,
    )
    fun searchByFoodKeywordAndStatus(
        @Param("keyword") keyword: String,
        @Param("foodId") foodId: Long?,
        @Param("outboxStatus") outboxStatus: FoodVectorOutboxStatus,
        pageable: Pageable,
    ): Page<FoodVectorOutbox>

    fun findTop20ByOutboxStatusOrderByIdDesc(outboxStatus: FoodVectorOutboxStatus): List<FoodVectorOutbox>

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
