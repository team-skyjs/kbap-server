// 영속 어댑터 모듈(persistence) — ORM(JPA) 기술을 여기로 모은다.
// 클린아키텍처: 각 도메인 모듈을 의존하고 도메인 port(리포지토리 인터페이스)를 구현(adapter)하며,
// 영속성 엔티티 관리 책임을 갖는다. 모든 JPA 엔티티가 한 모듈에 모이므로 공통 BaseEntity(@MappedSuperclass)도 여기 둔다(상속).
// 부트 앱 presentation 이 이 모듈을 runtimeOnly 로 조립해 adapter 빈을 DI 한다(batch 는 의존하지 않는 디커플드 앱).
plugins {
    id("meogo.spring-conventions")
    // JPA no-arg: @Entity/@MappedSuperclass 에 합성 no-arg 생성자를 부여한다.
    id("org.jetbrains.kotlin.plugin.jpa")
    // testFixtures 소스셋: MySQL Testcontainers 공통 설정을 app:api 와 공유(KB-46).
    `java-test-fixtures`
}

dependencies {
    "implementation"(project(":core:kernel"))
    "implementation"(project(":core:food"))
    "implementation"(project(":core:avoidance"))
    "implementation"(project(":core:member"))

    "implementation"(libs.spring.boot.starter.data.jpa)
    // refresh token 저장소(KB-118): Redis TTL 로 토큰 수명 = 저장 수명을 맞춘다.
    "implementation"(libs.spring.boot.starter.data.redis)

    "runtimeOnly"(libs.mysql.connector)

    // 공유 컨테이너 설정(testFixtures): @ServiceConnection + Kotest 베이스. 소비 모듈이 testImplementation(testFixtures(...)) 로 가져간다.
    // dependency-management 가 testFixtures 구성엔 자동 적용되지 않아 Boot BOM 을 platform 으로 직접 얹어 버전을 해석한다.
    "testFixturesApi"(platform(libs.spring.boot.dependencies))
    "testFixturesApi"(libs.spring.boot.testcontainers)
    "testFixturesApi"(libs.testcontainers.mysql)
    "testFixturesApi"(libs.testcontainers.core)
    "testFixturesApi"(libs.spring.boot.starter.test)
    "testFixturesApi"(libs.kotest.extensions.spring)
    "testFixturesApi"(libs.kotest.runner.junit5)

    // 통합 테스트가 MySQL 드라이버로 컨테이너에 접속.
    "testRuntimeOnly"(libs.mysql.connector)
}
