// bookmark 도메인 컨텍스트(음식 북마크). 공통 설정(core 의존·spring/jpa·mysql)은 컨벤션 플러그인에서 온다.
plugins {
    id("kbap.domain-conventions")
}

dependencies {
    "api"(project(":domain:food"))              // 북마크 목록 = 음식 요약(FoodService 경유)
    "implementation"(project(":domain:member")) // 기피 성분 조회(MemberService.getAvoidedCodes)
    "implementation"(project(":domain:avoidance")) // 성분 식별자 enum 대조(BookmarkService) — 전이 누수였던 것을 명시

}
