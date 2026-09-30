package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.Order
import org.springframework.data.domain.Limit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun existsByImagePath(imagePath: String): Boolean

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            UPDATE orders SET latitude = NULL, longitude = NULL, road_address = NULL,
                place_source = NULL, place_external_id = NULL, place_name = NULL, place_address = NULL, place_language = NULL
            WHERE member_id = :memberId
        """,
    )
    fun eraseLocationByMemberId(@Param("memberId") memberId: Long): Int

    fun findByMemberId(memberId: Long, pageable: Pageable): Page<Order>

    fun countByMemberId(memberId: Long): Long

    @Query(
        """
        select o from Order o
        where o.memberId = :memberId
          and (:cursor is null or o.id < :cursor)
        order by o.id desc
        """,
    )
    fun findPageByMemberId(
        @Param("memberId") memberId: Long,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Order>

    @Query(
        """
        select o from Order o
        where o.createdAt between :from and :to
          and o.id > :afterId
          and not exists (
            select 1 from Notification n
            where n.memberId = o.memberId
              and n.type = com.kbap.common.domain.notification.model.NotificationType.REVIEW_REMINDER
              and n.createdAt >= o.createdAt
          )
          and exists (
            select 1 from OrderItem oi
            where oi.orderId = o.id
              and not exists (
                select 1 from Review r
                where r.memberId = o.memberId and r.foodId = oi.foodId
              )
          )
        order by o.id
        """,
    )
    fun findReviewReminderTargets(
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
        @Param("afterId") afterId: Long,
        limit: Limit,
    ): List<Order>
}
