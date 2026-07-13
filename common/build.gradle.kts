// kbap-common — 통합 이벤트·공통 DTO·기술 공통(유틸·횡단 어노테이션·logback 조각).
// kbap-api·kbap-batch 가 공유한다. web·jpa·도메인 의존 금지(가볍게 유지 → 디커플드 컨슈머도 안전).
// Spring-free: kotlin-common 컨벤션만 적용. 실제 계약 클래스를 추가할 때 jackson 등 의존을 더한다.
plugins {
    id("kbap.kotlin-common")
}
