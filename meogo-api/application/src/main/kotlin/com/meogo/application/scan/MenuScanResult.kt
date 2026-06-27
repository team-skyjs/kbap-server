package com.meogo.application.scan

import com.meogo.core.risk.RiskLevel

/**
 * 스캔 제출 결과(application 레벨 타입). results 는 요청 itemId 로 1:1 매칭된다.
 */
data class MenuScanResult(
    val scanId: Long,
    val results: List<ItemResult>,
) {
    data class ItemResult(
        val itemId: Int,
        val riskLevel: RiskLevel,
        val reason: String,
    )
}
