package com.meogo.domain.scan

import com.meogo.core.risk.RiskLevel

/**
 * 메뉴 항목 판정 스냅샷(값 객체). 이번 슬라이스에서는 application 의 mock 판정 결과를 담는다.
 * 후속에 실제 assessment 컨텍스트 결과로 교체돼도 도메인 표현은 동일하다.
 */
data class MenuItemAssessment(
    val riskLevel: RiskLevel,
    val reason: String,
)
