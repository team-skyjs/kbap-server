package com.meogo.infra.persistence.scan

import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.infra.persistence.BaseEntity
import com.meogo.core.scan.BoundingBox
import com.meogo.core.scan.MenuItemAssessment
import com.meogo.core.scan.MenuItemMatch
import com.meogo.core.scan.ScannedMenuItem
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

    @Column(name = "risk_level", nullable = false, length = 10)
    var riskLevel: String = "",

    @Column(name = "reason", nullable = false, length = 500)
    var reason: String = "",

    @Column(name = "match_status", nullable = false, length = 20)
    var matchStatus: String = MATCH_NOT_FOOD,

    @Column(name = "matched_food_id")
    var matchedFoodId: Long? = null,
) : BaseEntity() {
    fun toDomain(): ScannedMenuItem =
        ScannedMenuItem(
            id = id,
            itemId = itemId,
            rawMenuName = rawMenuName,
            boundingBox = BoundingBox(bboxX, bboxY, bboxWidth, bboxHeight),
            assessment = MenuItemAssessment(RiskLevel.valueOf(riskLevel), reason),
            match = toMatch(),
        )

    private fun toMatch(): MenuItemMatch =
        when (matchStatus) {
            MATCH_MATCHED -> MenuItemMatch.Matched(requireFoodId(MATCH_MATCHED))
            MATCH_PENDING -> MenuItemMatch.Pending(matchedFoodId)
            else -> MenuItemMatch.NotFood
        }

    private fun requireFoodId(status: String): Long =
        requireNotNull(matchedFoodId) { "$status 항목에 matched_food_id 가 없습니다" }

    companion object {
        private const val MATCH_MATCHED = "MATCHED"
        private const val MATCH_PENDING = "PENDING"
        private const val MATCH_NOT_FOOD = "NOT_FOOD"

        fun from(item: ScannedMenuItem): ScannedMenuItemJpaEntity =
            ScannedMenuItemJpaEntity(
                itemId = item.itemId,
                rawMenuName = item.rawMenuName,
                bboxX = item.boundingBox.x,
                bboxY = item.boundingBox.y,
                bboxWidth = item.boundingBox.width,
                bboxHeight = item.boundingBox.height,
                riskLevel = item.assessment.riskLevel.name,
                reason = item.assessment.reason,
                matchStatus = statusOf(item.match),
                matchedFoodId = foodIdOf(item.match),
            )

        private fun statusOf(match: MenuItemMatch): String =
            when (match) {
                is MenuItemMatch.Matched -> MATCH_MATCHED
                is MenuItemMatch.Pending -> MATCH_PENDING
                MenuItemMatch.NotFood -> MATCH_NOT_FOOD
            }

        private fun foodIdOf(match: MenuItemMatch): Long? =
            when (match) {
                is MenuItemMatch.Matched -> match.foodId
                is MenuItemMatch.Pending -> match.foodId
                MenuItemMatch.NotFood -> null
            }
    }
}
