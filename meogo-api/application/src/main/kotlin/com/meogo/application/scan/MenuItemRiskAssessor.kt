package com.meogo.application.scan

import com.meogo.domain.scan.MenuItemAssessment

/**
 * 메뉴 항목 판정 seam(FR-013). 후속에 실제 assessment 컨텍스트 호출 구현으로 교체된다.
 * 도메인(scan)·web(api)는 이 결과를 받기만 하고 판정 로직을 갖지 않는다.
 */
interface MenuItemRiskAssessor {
    /** @param index 요청 배열 0-based 순서. */
    fun assess(index: Int, rawMenuName: String): MenuItemAssessment
}
