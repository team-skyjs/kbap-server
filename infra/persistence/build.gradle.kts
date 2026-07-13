// 영속 어댑터 모듈(persistence) — KB-134 에서 해체 중(US1 완료 시 삭제 예정, ADR-0012).
// 남은 엔티티·리포지토리·어댑터가 도메인 모듈로 이관되는 동안만 유지된다.
plugins {
    id("meogo.spring-conventions")
    // JPA no-arg: @Entity/@MappedSuperclass 에 합성 no-arg 생성자를 부여한다.
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    "implementation"(project(":core"))
    "implementation"(project(":domain:food"))
    "implementation"(project(":domain:avoidance"))
    "implementation"(project(":domain:member"))
    "implementation"(project(":domain:scan"))

    "implementation"(libs.spring.boot.starter.data.jpa)
    // refresh token 저장소(KB-118): Redis TTL 로 토큰 수명 = 저장 수명을 맞춘다.
    "implementation"(libs.spring.boot.starter.data.redis)

    "runtimeOnly"(libs.mysql.connector)

    // 컨테이너 공통 설정은 :core testFixtures 로 이동(KB-134).
    "testImplementation"(testFixtures(project(":core")))

    // 통합 테스트가 MySQL 드라이버로 컨테이너에 접속.
    "testRuntimeOnly"(libs.mysql.connector)
}
