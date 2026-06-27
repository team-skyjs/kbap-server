package com.meogo.api.scan.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "scanned_menu_item")
class ScannedMenuItemJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "item_id", nullable = false)
    var itemId: Int = 0,

    @Column(name = "raw_menu_name", nullable = false)
    var rawMenuName: String = "",

    @Column(name = "bbox_x", nullable = false)
    var bboxX: Double = 0.0,

    @Column(name = "bbox_y", nullable = false)
    var bboxY: Double = 0.0,

    @Column(name = "bbox_width", nullable = false)
    var bboxWidth: Double = 0.0,

    @Column(name = "bbox_height", nullable = false)
    var bboxHeight: Double = 0.0,

    @Column(name = "received_order", nullable = false)
    var receivedOrder: Int = 0,

    @Column(name = "risk_level", nullable = false)
    var riskLevel: String = "",

    @Column(name = "reason", nullable = false)
    var reason: String = "",
)
