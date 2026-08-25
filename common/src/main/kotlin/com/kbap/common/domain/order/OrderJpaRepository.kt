package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.Order
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OrderJpaRepository : JpaRepository<Order, Long> {
    @Query(
        """
        select o from Order o
        where (:memberId is null or o.memberId = :memberId)
          and (:from is null or o.createdAt >= :from)
          and (:to is null or o.createdAt < :to)
        order by o.id desc
        """,
    )
    fun findAdminPage(
        @Param("memberId") memberId: Long?,
        @Param("from") from: LocalDateTime?,
        @Param("to") to: LocalDateTime?,
        pageable: Pageable,
    ): Page<Order>

    fun existsByImagePath(imagePath: String): Boolean

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
}
