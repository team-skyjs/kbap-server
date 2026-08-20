package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.Order
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun countByMemberId(memberId: Long): Long

    fun existsByImagePath(imagePath: String): Boolean

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
