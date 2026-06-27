package com.meogo.application.scan

import com.meogo.domain.scan.BoundingBox
import com.meogo.domain.scan.MenuScan
import com.meogo.domain.scan.MenuScanRepository
import com.meogo.domain.scan.ScannedMenuItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SubmitMenuScanUseCase(
    private val menuScanRepository: MenuScanRepository,
    private val riskAssessor: MenuItemRiskAssessor,
) {
    @Transactional
    fun submit(command: SubmitMenuScanCommand): MenuScanResult {
        val items = command.items.mapIndexed { index, item ->
            ScannedMenuItem(
                itemId = item.itemId,
                rawMenuName = item.rawMenuName,
                boundingBox = BoundingBox(
                    x = item.boundingBox.x,
                    y = item.boundingBox.y,
                    width = item.boundingBox.width,
                    height = item.boundingBox.height,
                ),
                receivedOrder = index,
                assessment = riskAssessor.assess(index, item.rawMenuName),
            )
        }

        val saved = menuScanRepository.save(MenuScan.create(items))

        return MenuScanResult(
            scanId = requireNotNull(saved.id) { "저장된 스캔에 id 가 없습니다" },
            results = saved.items.map { scannedItem ->
                MenuScanResult.ItemResult(
                    itemId = scannedItem.itemId,
                    riskLevel = scannedItem.assessment.riskLevel,
                    reason = scannedItem.assessment.reason,
                )
            },
        )
    }
}
