package com.meogo.application.client.scan.usecase

import com.meogo.application.client.scan.dto.SubmitMenuScanInput
import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import com.meogo.core.scan.BoundingBox
import com.meogo.core.scan.MenuScan
import com.meogo.core.scan.MenuScanRepository
import com.meogo.core.scan.ScannedMenuItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SubmitMenuScanUseCase(
    private val menuScanRepository: MenuScanRepository,
    private val riskAssessor: MockCyclingRiskAssessor,
) {
    @Transactional
    fun submit(input: SubmitMenuScanInput): SubmitMenuScanResult {
        val items = input.items.mapIndexed { index, item ->
            val box = item.boundingBox
            ScannedMenuItem(
                itemId = item.itemId,
                rawMenuName = item.rawMenuName,
                boundingBox = BoundingBox(x = box.x, y = box.y, width = box.width, height = box.height),
                assessment = riskAssessor.assess(index, item.rawMenuName),
            )
        }

        return MenuScan.CreationSpec(items)
            .let(MenuScan::create)
            .let(menuScanRepository::save)
            .let(SubmitMenuScanResult::from)
    }
}
