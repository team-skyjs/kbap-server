// food 도메인 컨텍스트. 공통 설정(core 의존·spring/jpa·mysql)은 컨벤션 플러그인에서 온다.
plugins {
    id("kbap.domain-conventions")
}

dependencies {
    "api"(project(":domain:member"))    // 회원 기피 성분 → 위험도 판정
    "api"(project(":domain:avoidance")) // 성분 카탈로그 표시명
}
