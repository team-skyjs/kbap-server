plugins {
    id("meogo.spring-conventions")
}

dependencies {
    // 도메인/코어/공통은 내부 구현으로만 쓴다(implementation) — application 의 공개 API 는
    // Command/Result 같은 application 레벨 타입만 노출하고, 도메인 엔티티가 상위(api)의
    // 컴파일 클래스패스로 새지 않게 한다(런타임 전이는 정상 → 빈/스캔/JPA 동작).
    // 컨텍스트 간 조합은 여기서만 한다. 도메인 모듈끼리는 서로 의존하지 않는다.
    "implementation"(project(":core:food"))
    "implementation"(project(":core:member"))
    "implementation"(project(":core:scan"))
    "implementation"(project(":core:assessment"))
    "implementation"(project(":core:review"))
    "implementation"(project(":core:kernel"))
    "implementation"(project(":common"))

    // 유스케이스 조립·트랜잭션 경계용 최소 Spring(@Service/@Component/@Transactional).
    // web/jpa 스타터는 얹지 않는다(계층 경계 유지) — 실제 트랜잭션 매니저는 부트 앱이 런타임 주입.
    "implementation"(libs.spring.context)
    "implementation"(libs.spring.tx)
}
