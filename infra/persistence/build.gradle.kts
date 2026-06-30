// 영속 어댑터 모듈(persistence) — ORM(JPA) 기술을 여기로 모은다.
// 클린아키텍처: 각 도메인 모듈을 의존하고 도메인 port(리포지토리 인터페이스)를 구현(adapter)하며,
// 영속성 엔티티 관리 책임을 갖는다. 모든 JPA 엔티티가 한 모듈에 모이므로 공통 BaseEntity(@MappedSuperclass)도 여기 둔다(상속).
// 부트 앱 presentation 이 이 모듈을 runtimeOnly 로 조립해 adapter 빈을 DI 한다(batch 는 의존하지 않는 디커플드 앱).
plugins {
    id("meogo.spring-conventions")
    // JPA no-arg: @Entity/@MappedSuperclass 에 합성 no-arg 생성자를 부여한다.
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    "implementation"(project(":core:kernel"))
    "implementation"(project(":core:scan"))
    "implementation"(project(":core:food"))
    "implementation"(project(":core:avoidance"))

    "implementation"(libs.spring.boot.starter.data.jpa)

    "runtimeOnly"(libs.mysql.connector)
    "testRuntimeOnly"(libs.h2)
}
