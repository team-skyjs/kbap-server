plugins {
    // web bootJar (조립 모듈). 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("meogo.spring-boot-application")
}

dependencies {
    "implementation"(project(":application:client"))
    "implementation"(project(":core:kernel"))
    "implementation"(project(":common"))

    // 조립: persistence adapter 빈을 런타임 클래스패스에만 올려 DI 로 연결한다(컴파일 의존 X).
    // (infra:external 은 LLM 착수 시 추가)
    "runtimeOnly"(project(":infra:persistence"))

    "implementation"(libs.spring.boot.starter.web)
    "implementation"(libs.spring.boot.starter.validation)
    "implementation"(libs.spring.boot.starter.actuator)
    "implementation"(libs.springdoc.openapi.starter.webmvc.ui)

    // DB 마이그레이션 — 스키마 owner 는 api. 마이그레이션 파일은 resources/db/migration.
    // Boot 4 는 Flyway autoconfig 가 별도 모듈(spring-boot-flyway) — 없으면 마이그레이션이 자동 실행되지 않는다.
    "implementation"(libs.spring.boot.flyway)
    "implementation"(libs.flyway.core)
    "runtimeOnly"(libs.flyway.mysql)

    "testRuntimeOnly"(libs.h2)

    // @AutoConfigureMockMvc — Boot 4.x 에서 web mvc test-slice 가 별도 모듈로 분리됐다.
    "testImplementation"(libs.spring.boot.webmvc.test)

    // ArchUnit 모듈 경계 테스트(ADR-0008). app:api 는 조립 모듈이라 런타임에 전 모듈 클래스를
    // 이미 보므로(application:client 전이 + infra:persistence runtimeOnly) com.meogo 전체를 스캔할 수 있다.
    "testImplementation"(libs.archunit)

    // avoidance 회귀 테스트가 :core:avoidance 를 직접 참조한다: 코드↔V5 시드 정합(AvoidanceCatalogSeedSyncTest)이
    // AvoidanceSubstanceCode 를, ModuleBoundaryTest 가 성분 식별자 enum 데이터 없음·엔티티 분류 저장 형식을 검증한다.
    "testImplementation"(project(":core:avoidance"))
}

// 루트의 .env(application.yml 의 spring.config.import 대상)를 찾도록
// bootRun 작업 디렉터리를 멀티모듈 루트로 맞춘다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
