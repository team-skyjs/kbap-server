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
        value = "SELECT outbox.* FROM food_content_outbox outbox WHERE $DEAD_UNRESOLVED ORDER BY outbox.id DESC",
        countQuery = "SELECT COUNT(*) FROM food_content_outbox outbox WHERE $DEAD_UNRESOLVED",
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

    @Query(
        value = "SELECT outbox.* FROM food_content_outbox outbox WHERE $STALE_SENT ORDER BY outbox.id ASC LIMIT :limit",
        nativeQuery = true,
    )
    fun findStaleSent(@Param("before") before: LocalDateTime, @Param("limit") limit: Int): List<FoodContentOutbox>

    @Query(value = "SELECT COUNT(*) FROM food_content_outbox outbox WHERE $STALE_SENT", nativeQuery = true)
    fun countStaleSent(@Param("before") before: LocalDateTime): Long

    @Query(value = "SELECT COUNT(*) FROM food_content_outbox outbox WHERE $DEAD_UNRESOLVED", nativeQuery = true)
    fun countDead(): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox outbox
            SET outbox.outbox_status = 'PENDING',
                outbox.sent_at = NULL,
                outbox.last_error = :reason,
                outbox.updated_at = CURRENT_TIMESTAMP(6)
            WHERE outbox.id = :id AND $STALE_SENT
        """,
        nativeQuery = true,
    )
    fun requeueIfStillStale(
        @Param("id") id: Long,
        @Param("before") before: LocalDateTime,
        @Param("reason") reason: String,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox outbox
            SET outbox.dead_at = CURRENT_TIMESTAMP(6),
                outbox.last_error = :reason,
                outbox.updated_at = CURRENT_TIMESTAMP(6)
            WHERE outbox.id = :id AND $STALE_SENT
        """,
        nativeQuery = true,
    )
    fun markDeadIfStillStale(
        @Param("id") id: Long,
        @Param("before") before: LocalDateTime,
        @Param("reason") reason: String,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE food_content_outbox outbox
            SET outbox.outbox_status = 'COMPLETE',
                outbox.updated_at = CURRENT_TIMESTAMP(6)
            WHERE outbox.id = :outboxId
              AND outbox.food_id = :foodId
              AND outbox.outbox_status IN ('PENDING', 'SENT')
              AND $NOT_DEAD
              AND $LIVE_REQUEST
        """,
        nativeQuery = true,
    )
    fun completeIfProcessable(
        @Param("outboxId") outboxId: Long,
        @Param("foodId") foodId: Long,
    ): Int

    fun existsByIdAndFoodIdAndDeadAtIsNotNull(id: Long, foodId: Long): Boolean

    @Query(
        value = """
            SELECT COUNT(*) FROM food_content_outbox outbox
            WHERE outbox.id = :outboxId AND outbox.food_id = :foodId AND outbox.status = 'ACTIVE'
              AND NOT ($NOT_SUPERSEDED)
        """,
        nativeQuery = true,
    )
    fun countSuperseded(@Param("outboxId") outboxId: Long, @Param("foodId") foodId: Long): Long

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
        private const val NOT_SUPERSEDED =
            "NOT EXISTS (SELECT 1 FROM (SELECT id, food_id, status FROM food_content_outbox) newer " +
                "WHERE newer.food_id = outbox.food_id AND newer.id > outbox.id AND newer.status = 'ACTIVE')"

        private const val LIVE_REQUEST =
            "outbox.status = 'ACTIVE' " +
                "AND EXISTS (SELECT 1 FROM food f WHERE f.id = outbox.food_id AND f.status = 'ACTIVE') " +
                "AND $NOT_SUPERSEDED"

        const val NOT_DEAD = "outbox.dead_at IS NULL"

        const val STALE_SENT =
            "outbox.outbox_status = 'SENT' AND $NOT_DEAD AND outbox.sent_at IS NOT NULL " +
                "AND outbox.sent_at < :before AND $LIVE_REQUEST"

        const val DEAD_UNRESOLVED =
            "outbox.dead_at IS NOT NULL AND outbox.outbox_status <> 'COMPLETE' AND $LIVE_REQUEST"
    }
}
