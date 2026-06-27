package com.meogo.core.risk

/**
 * 메뉴 항목·재료의 위험도 4단계(고정). 컨텍스트 공유 커널 타입(Spring-free).
 * scan 판정 결과·후속 assessment·food 재료 상태에서 공통으로 사용한다.
 */
enum class RiskLevel {
    SAFE,
    CAUTION,
    DANGER,
    UNKNOWN,
}
