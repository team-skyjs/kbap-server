package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodContentOutboxJpaRepository : JpaRepository<FoodContentOutbox, Long> {
    fun existsByFoodIdAndOutboxStatus(foodId: Long, outboxStatus: FoodContentOutboxStatus): Boolean

    fun findByFoodIdInAndOutboxStatus(foodIds: Collection<Long>, outboxStatus: FoodContentOutboxStatus): List<FoodContentOutbox>

    fun findByOutboxStatusOrderByIdAsc(outboxStatus: FoodContentOutboxStatus): List<FoodContentOutbox>

    fun countByOutboxStatus(outboxStatus: FoodContentOutboxStatus): Long

    fun findTop20ByOrderByIdDesc(): List<FoodContentOutbox>

    fun findByOutboxStatus(outboxStatus: FoodContentOutboxStatus, pageable: Pageable): Page<FoodContentOutbox>

    @Query(
        """
        select o from FoodContentOutbox o
        where (:foodId is not null and o.foodId = :foodId)
           or o.displayName like concat('%', :keyword, '%') escape '\'
        """,
    )
    fun searchByKeyword(
        @Param("keyword") keyword: String,
        @Param("foodId") foodId: Long?,
        pageable: Pageable,
    ): Page<FoodContentOutbox>

    @Query(
        """
        select o from FoodContentOutbox o
        where o.outboxStatus = :outboxStatus
          and (
              (:foodId is not null and o.foodId = :foodId)
              or o.displayName like concat('%', :keyword, '%') escape '\'
          )
        """,
    )
    fun searchByKeywordAndStatus(
        @Param("keyword") keyword: String,
        @Param("foodId") foodId: Long?,
        @Param("outboxStatus") outboxStatus: FoodContentOutboxStatus,
        pageable: Pageable,
    ): Page<FoodContentOutbox>

    @Query(
        value = """
            SELECT *
            FROM food_content_outbox
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
    ): List<FoodContentOutbox>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox
            SET outbox_status = 'COMPLETE',
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :outboxId
              AND food_id = :foodId
              AND outbox_status IN ('PENDING', 'SENT')
              AND status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun completeIfProcessable(
        @Param("outboxId") outboxId: Long,
        @Param("foodId") foodId: Long,
    ): Int

    fun existsByIdAndFoodIdAndOutboxStatus(
        id: Long,
        foodId: Long,
        outboxStatus: FoodContentOutboxStatus,
    ): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox
            SET attempts = attempts + 1,
                sent_at = COALESCE(sent_at, CURRENT_TIMESTAMP(6)),
                outbox_status = CASE
                    WHEN outbox_status = 'PENDING' THEN 'SENT'
                    ELSE outbox_status
                END,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id IN (:ids)
              AND outbox_status IN ('PENDING', 'SENT', 'COMPLETE')
              AND status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun recordPublishSucceeded(@Param("ids") ids: Collection<Long>): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox
            SET attempts = attempts + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id IN (:ids)
              AND outbox_status IN ('PENDING', 'SENT', 'COMPLETE')
              AND status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun recordPublishFailed(@Param("ids") ids: Collection<Long>): Int
}
