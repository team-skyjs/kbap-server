package com.kbap.common.domain.order.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "order_photo")
class OrderPhoto(
    @Column(name = "order_id", nullable = false)
    val orderId: Long = 0,

    @Column(name = "operation_photo_id", nullable = false)
    val operationPhotoId: Long = 0,

    @Column(name = "selection_index", nullable = false)
    val selectionIndex: Int = 0,

    @Column(name = "object_key", nullable = false, length = 512)
    val objectKey: String = "",
) : BaseEntity()
