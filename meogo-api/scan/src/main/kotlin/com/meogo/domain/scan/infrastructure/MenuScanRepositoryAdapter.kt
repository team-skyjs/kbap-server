package com.meogo.domain.scan.infrastructure

import com.meogo.core.risk.RiskLevel
import com.meogo.domain.scan.BoundingBox
import com.meogo.domain.scan.MenuItemAssessment
import com.meogo.domain.scan.MenuScan
import com.meogo.domain.scan.MenuScanRepository
import com.meogo.domain.scan.ScanStatus
import com.meogo.domain.scan.ScannedMenuItem
import org.springframework.stereotype.Repository

/**
 * [MenuScanRepository] 구현 — 도메인 ⇄ JPA 매핑을 이 모듈 안에 가둔다(헌법 IV).
 */
@Repository
class MenuScanRepositoryAdapter(
    private val jpaRepository: MenuScanJpaRepository,
) : MenuScanRepository {

    override fun save(menuScan: MenuScan): MenuScan =
        jpaRepository.save(menuScan.toEntity()).toDomain()

    override fun findById(scanId: Long): MenuScan? =
        jpaRepository.findById(scanId).map { it.toDomain() }.orElse(null)
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
