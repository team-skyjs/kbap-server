package com.meogo.application.scan

import com.meogo.domain.scan.BoundingBox
import com.meogo.domain.scan.MenuScan
import com.meogo.domain.scan.MenuScanRepository
import com.meogo.domain.scan.ScannedMenuItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 스캔 제출 유스케이스: command → 항목별 mock 판정 부여 → 도메인 조립 → 저장 → itemId 매칭 결과 반환.
 * 트랜잭션 경계는 이 유스케이스 한 번(헌법 III·외부 호출 없음).
 */
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
