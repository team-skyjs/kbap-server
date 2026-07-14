// member 도메인 컨텍스트. 공통 설정은 컨벤션 플러그인에서 온다.
plugins {
    id("kbap.domain-conventions")
}

dependencies {
    "api"(project(":domain:avoidance")) // 기피 성분 코드 enum — 프로필 검증·조회 반환 타입

}
