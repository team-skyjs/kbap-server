// metering 도메인 컨텍스트(외부 자원 사용량·비용 계량 원장, KB-155). 리프 — 도메인 간 의존 없음.
// 공통 설정(core 의존 + 영속)은 컨벤션 플러그인에서 온다. 기록 트리거는 :core 의 LlmCallCostIncurred 이벤트.
plugins {
    id("kbap.domain-conventions")
}
