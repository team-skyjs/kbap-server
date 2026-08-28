package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface FoodContentOutboxJpaRepository : JpaRepository<FoodContentOutbox, Long> {
    fun existsByFoodIdAndOutboxStatus(foodId: Long, outboxStatus: FoodContentOutboxStatus): Boolean

    fun findByFoodIdInAndOutboxStatus(foodIds: Collection<Long>, outboxStatus: FoodContentOutboxStatus): List<FoodContentOutbox>

    fun findByOutboxStatusOrderByIdAsc(outboxStatus: FoodContentOutboxStatus): List<FoodContentOutbox>

    fun countByOutboxStatus(outboxStatus: FoodContentOutboxStatus): Long

    fun findTop20ByOrderByIdDesc(): List<FoodContentOutbox>

    fun findTop20ByOutboxStatusAndSentAtBeforeOrderBySentAtAsc(outboxStatus: FoodContentOutboxStatus, before: LocalDateTime): List<FoodContentOutbox>

    fun findTop14ByOutboxStatusInOrderByIdDesc(statuses: Collection<FoodContentOutboxStatus>): List<FoodContentOutbox>

    fun findTop10ByFoodIdOrderByIdDesc(foodId: Long): List<FoodContentOutbox>

    fun countByOutboxStatusAndSentAtBefore(outboxStatus: FoodContentOutboxStatus, before: LocalDateTime): Long

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
                last_error = :error,
                last_failed_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id IN (:ids)
              AND outbox_status IN ('PENDING', 'SENT', 'COMPLETE')
              AND status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun recordPublishFailed(@Param("ids") ids: Collection<Long>, @Param("error") error: String?): Int

    fun recordPublishFailed(ids: Collection<Long>): Int = recordPublishFailed(ids, null)

    @Query(
        """
        select o from FoodContentOutbox o
        where (:status is null or o.outboxStatus = :status)
          and (:foodId is null or o.foodId = :foodId)
        order by o.id desc
        """,
    )
    fun findPage(
        @Param("status") status: FoodContentOutboxStatus?,
        @Param("foodId") foodId: Long?,
        pageable: Pageable,
    ): Page<FoodContentOutbox>
}
