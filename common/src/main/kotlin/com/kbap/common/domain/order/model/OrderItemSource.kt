package com.kbap.common.domain.order.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "order_item_source")
class OrderItemSource(
    @Column(name = "order_item_id", nullable = false)
    val orderItemId: Long = 0,

    @Column(name = "occurrence_id", nullable = false)
    val occurrenceId: Long = 0,
) : BaseEntity()
