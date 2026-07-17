plugins {
    // web bootJar (조립 모듈). 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("kbap.spring-boot-application")
}

dependencies {
    "implementation"(project(":application"))
    "implementation"(project(":core"))

    // 스캔 메뉴명 정제 LLM 어댑터(ScannedNameInterpreter). @ConditionalOnProperty 로 미구성 시 빈 미생성.
    "runtimeOnly"(project(":infra:llm"))

    // 인증 구현 어댑터 — config/AuthConfig 가 Firebase 팩토리를 직접 조립하므로 compile 의존.
    "implementation"(project(":infra:auth"))

    // Redis 어댑터(RefreshTokenStore 구현) — 런타임 조립.
    "runtimeOnly"(project(":infra:redis"))

    // 스토리지 어댑터(StorageObjectStore 구현) — config/StorageConfig 가 S3Client 를 직접 조립하므로 compile 의존.
    "implementation"(project(":infra:storage"))

    // 컨트롤러가 도메인 서비스를 직접 호출한다(도메인 간 단방향 의존 구조 전환).
    "implementation"(project(":domain:member"))
    "implementation"(project(":domain:food"))
    "implementation"(project(":domain:scan"))
    "implementation"(project(":domain:bookmark"))
    "implementation"(project(":domain:image"))
    "implementation"(project(":domain:metering"))

    "implementation"(libs.spring.boot.starter.web)
    "implementation"(libs.spring.boot.starter.validation)
    "implementation"(libs.spring.boot.starter.actuator)
    "implementation"(libs.springdoc.openapi.starter.webmvc.ui)

    // DB 마이그레이션 — 스키마 owner 는 api. 마이그레이션 파일은 resources/db/migration.
    // Boot 4 는 Flyway autoconfig 가 별도 모듈(spring-boot-flyway) — 없으면 마이그레이션이 자동 실행되지 않는다.
    "implementation"(libs.spring.boot.flyway)
    "implementation"(libs.flyway.core)
    "runtimeOnly"(libs.flyway.mysql)

    // 통합 테스트 DB: MySQL Testcontainers 공통 설정을 :core testFixtures 에서 가져온다(KB-46·KB-134).
    // testFixturesApi 로 노출된 spring-boot-testcontainers·testcontainers-mysql 이 전이된다.
    "testImplementation"(testFixtures(project(":core")))
    "testRuntimeOnly"(libs.mysql.connector)

    // @AutoConfigureMockMvc — Boot 4.x 에서 web mvc test-slice 가 별도 모듈로 분리됐다.
    "testImplementation"(libs.spring.boot.webmvc.test)

    // ArchUnit 모듈 경계 테스트(ADR-0008). app:api 는 조립 모듈이라 런타임에 전 모듈 클래스를
    // 이미 보므로(application:client 가 도메인 모듈을 런타임 전이) com.kbap 전체를 스캔할 수 있다.
    "testImplementation"(libs.archunit)

    // avoidance 회귀 테스트가 :domain:avoidance 를 직접 참조한다: 코드↔V5 시드 정합(AvoidanceCatalogSeedSyncTest)이
    // AvoidanceSubstanceCode 를, ModuleBoundaryTest 가 성분 식별자 enum 데이터 없음·엔티티 분류 저장 형식을 검증한다.
    "testImplementation"(project(":domain:avoidance"))

}

// 루트의 .env(application.yml 의 spring.config.import 대상)를 찾도록
// bootRun 작업 디렉터리를 멀티모듈 루트로 맞춘다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
