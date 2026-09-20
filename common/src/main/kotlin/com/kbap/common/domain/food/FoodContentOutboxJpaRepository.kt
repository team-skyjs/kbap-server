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

    fun findByOutboxStatus(outboxStatus: FoodContentOutboxStatus, pageable: Pageable): Page<FoodContentOutbox>

    @Query(
        value = "SELECT * FROM food_content_outbox dead WHERE $DEAD_UNRESOLVED ORDER BY dead.id DESC",
        countQuery = "SELECT COUNT(*) FROM food_content_outbox dead WHERE $DEAD_UNRESOLVED",
        nativeQuery = true,
    )
    fun findDeadPage(pageable: Pageable): Page<FoodContentOutbox>

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

    @Query(value = "SELECT * FROM food_content_outbox WHERE $STALE_SENT ORDER BY id ASC LIMIT :limit", nativeQuery = true)
    fun findStaleSent(@Param("before") before: LocalDateTime, @Param("limit") limit: Int): List<FoodContentOutbox>

    @Query(value = "SELECT COUNT(*) FROM food_content_outbox WHERE $STALE_SENT", nativeQuery = true)
    fun countStaleSent(@Param("before") before: LocalDateTime): Long

    @Query(value = "SELECT COUNT(*) FROM food_content_outbox dead WHERE $DEAD_UNRESOLVED", nativeQuery = true)
    fun countDead(): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox
            SET outbox_status = 'PENDING',
                sent_at = NULL,
                last_error = :reason,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id AND outbox_status = 'SENT' AND dead_at IS NULL AND status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun requeueIfStillSent(@Param("id") id: Long, @Param("reason") reason: String): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox
            SET dead_at = CURRENT_TIMESTAMP(6),
                last_error = :reason,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id AND outbox_status = 'SENT' AND dead_at IS NULL AND status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun markDeadIfStillSent(@Param("id") id: Long, @Param("reason") reason: String): Int

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

    companion object {
        const val DEAD_UNRESOLVED =
            "dead.dead_at IS NOT NULL AND dead.outbox_status <> 'COMPLETE' AND dead.status = 'ACTIVE' " +
                "AND NOT EXISTS (SELECT 1 FROM food_content_outbox newer " +
                "WHERE newer.food_id = dead.food_id AND newer.id > dead.id AND newer.status = 'ACTIVE')"

        const val STALE_SENT =
            "outbox_status = 'SENT' AND dead_at IS NULL AND sent_at IS NOT NULL AND sent_at < :before " +
                "AND status = 'ACTIVE' " +
                "AND EXISTS (SELECT 1 FROM food f WHERE f.id = food_content_outbox.food_id AND f.status = 'ACTIVE')"
    }
}
