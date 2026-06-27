plugins {
    // web bootJar (조립 모듈). 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("meogo.spring-boot-application")
}

dependencies {
    "implementation"(project(":meogo-api:application"))
    "implementation"(project(":meogo-api:core"))
    "implementation"(project(":meogo-common"))

    // 조립: infra adapter 빈을 런타임 클래스패스에만 올려 DI 로 연결한다(컴파일 의존 X).
    "runtimeOnly"(project(":meogo-api:infra"))

    "implementation"(libs.spring.boot.starter.web)
    "implementation"(libs.spring.boot.starter.validation)
    "implementation"(libs.spring.boot.starter.actuator)
    "implementation"(libs.springdoc.openapi.starter.webmvc.ui)

    // DB 마이그레이션 — 스키마 owner 는 api. 마이그레이션 파일은 resources/db/migration.
    "implementation"(libs.flyway.core)
    "runtimeOnly"(libs.flyway.mysql)

    "testRuntimeOnly"(libs.h2)

    // @AutoConfigureMockMvc — Boot 4.x 에서 web mvc test-slice 가 별도 모듈로 분리됐다.
    "testImplementation"(libs.spring.boot.webmvc.test)
}

// 루트의 .env(application.yml 의 spring.config.import 대상)를 찾도록
// bootRun 작업 디렉터리를 멀티모듈 루트로 맞춘다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
