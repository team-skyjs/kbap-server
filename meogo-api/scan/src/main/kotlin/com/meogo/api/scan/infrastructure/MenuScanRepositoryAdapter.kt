package com.meogo.api.scan.infrastructure

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.scan.BoundingBox
import com.meogo.api.scan.MenuItemAssessment
import com.meogo.api.scan.MenuScan
import com.meogo.api.scan.MenuScanRepository
import com.meogo.api.scan.ScanStatus
import com.meogo.api.scan.ScannedMenuItem
import org.springframework.stereotype.Repository

@Repository
class MenuScanRepositoryAdapter(
    private val jpaRepository: MenuScanJpaRepository,
) : MenuScanRepository {
    override fun save(menuScan: MenuScan): MenuScan =
        jpaRepository.save(menuScan.toEntity()).toDomain()

    override fun findById(scanId: Long): MenuScan? =
        jpaRepository.findByIdWithItems(scanId)?.toDomain()
}

private fun MenuScan.toEntity(): MenuScanJpaEntity =
    MenuScanJpaEntity(
        status = status.name,
        createdAt = createdAt,
        items = items.map { it.toEntity() }.toMutableList(),
    )

private fun ScannedMenuItem.toEntity(): ScannedMenuItemJpaEntity =
    ScannedMenuItemJpaEntity(
        itemId = itemId,
        rawMenuName = rawMenuName,
        bboxX = boundingBox.x,
        bboxY = boundingBox.y,
        bboxWidth = boundingBox.width,
        bboxHeight = boundingBox.height,
        receivedOrder = receivedOrder,
        riskLevel = assessment.riskLevel.name,
        reason = assessment.reason,
    )

private fun MenuScanJpaEntity.toDomain(): MenuScan =
    MenuScan.reconstitute(
        id = id,
        status = ScanStatus.valueOf(status),
        items = items
            .sortedBy { it.receivedOrder }
            .map { it.toDomain() },
        createdAt = createdAt,
    )

private fun ScannedMenuItemJpaEntity.toDomain(): ScannedMenuItem =
    ScannedMenuItem(
        itemId = itemId,
        rawMenuName = rawMenuName,
        boundingBox = BoundingBox(bboxX, bboxY, bboxWidth, bboxHeight),
        receivedOrder = receivedOrder,
        assessment = MenuItemAssessment(RiskLevel.valueOf(riskLevel), reason),
    )
