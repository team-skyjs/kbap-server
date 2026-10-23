package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.OrderPhoto
import org.springframework.data.jpa.repository.JpaRepository

interface OrderPhotoJpaRepository : JpaRepository<OrderPhoto, Long> {
    fun findByOrderIdOrderBySelectionIndex(orderId: Long): List<OrderPhoto>
}
