package com.kbap.common.domain.processedfood.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "processed_food_image",
    indexes = [Index(name = "idx_processed_food_image_product", columnList = "processed_food_id, status, sort_order")],
)
class ProcessedFoodImage(
    @Column(name = "processed_food_id", nullable = false)
    val processedFoodId: Long = 0,

    @Column(name = "image_url", nullable = false, length = 500)
    val imageUrl: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    val kind: ProcessedFoodImageKind = ProcessedFoodImageKind.OTHER,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity()

enum class ProcessedFoodImageKind { CUTOUT, FRONT, TOP, LABEL, OTHER }
