package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.OrderItemSource
import org.springframework.data.jpa.repository.JpaRepository

interface OrderItemSourceJpaRepository : JpaRepository<OrderItemSource, Long>
