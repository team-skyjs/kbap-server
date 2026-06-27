package com.meogo.api.scan.infrastructure

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.persistence.BaseEntity
import com.meogo.api.scan.BoundingBox
import com.meogo.api.scan.MenuItemAssessment
import com.meogo.api.scan.ScannedMenuItem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "scanned_menu_item")
class ScannedMenuItemJpaEntity(
    @Column(name = "item_id", nullable = false)
    var itemId: Int = 0,

    @Column(name = "raw_menu_name", nullable = false, length = 255)
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

    @Column(name = "risk_level", nullable = false, length = 10)
    var riskLevel: String = "",

    @Column(name = "reason", nullable = false, length = 500)
    var reason: String = "",
) : BaseEntity() {
    fun toDomain(): ScannedMenuItem =
        ScannedMenuItem(
            itemId = itemId,
            rawMenuName = rawMenuName,
            boundingBox = BoundingBox(bboxX, bboxY, bboxWidth, bboxHeight),
            receivedOrder = receivedOrder,
            assessment = MenuItemAssessment(RiskLevel.valueOf(riskLevel), reason),
        )

    companion object {
        fun from(item: ScannedMenuItem): ScannedMenuItemJpaEntity =
            ScannedMenuItemJpaEntity(
                itemId = item.itemId,
                rawMenuName = item.rawMenuName,
                bboxX = item.boundingBox.x,
                bboxY = item.boundingBox.y,
                bboxWidth = item.boundingBox.width,
                bboxHeight = item.boundingBox.height,
                receivedOrder = item.receivedOrder,
                riskLevel = item.assessment.riskLevel.name,
                reason = item.assessment.reason,
            )
    }
}
