package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.OrderItem
import org.springframework.data.jpa.repository.JpaRepository

interface OrderItemJpaRepository : JpaRepository<OrderItem, Long> {
    fun findByOrderIdInOrderByIdAsc(orderIds: Collection<Long>): List<OrderItem>

    fun findByOrderIdOrderByIdAsc(orderId: Long): List<OrderItem>
}
