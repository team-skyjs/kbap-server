import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// ───────── :common 모듈 아키타입 (KB-244) ─────────
// 구 kbap.domain-conventions 를 승계한다 — 공유 커널(구 :core) + 공유 도메인(food·member·avoidance)
// + 외부 시스템 seam 이 한 모듈에 살므로, JPA(no-arg·allopen)·Boot BOM·data-jpa(api 전이)와
// 구 :core 의 testFixtures(MySQL Testcontainers 공통 설정)를 함께 제공한다.
plugins {
    id("kbap.kotlin-common")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("io.spring.dependency-management")
    `java-test-fixtures`
}

// Hibernate 프록시 경고(HHH000305) 방지: 엔티티 클래스·게터를 open 으로.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val springBootVersion = libs.findVersion("spring-boot").get().requiredVersion

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}

dependencies {
    "api"(libs.findLibrary("spring-boot-starter-data-jpa").get())
    "implementation"(libs.findLibrary("kotlin-reflect").get())
    "implementation"(libs.findLibrary("jackson-module-kotlin").get())
    "runtimeOnly"(libs.findLibrary("mysql-connector").get())

    // dependency-management 가 testFixtures 구성엔 자동 적용되지 않아 Boot BOM 을 platform 으로 직접 얹는다.
    "testFixturesApi"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    "testFixturesApi"(libs.findLibrary("spring-boot-testcontainers").get())
    "testFixturesApi"(libs.findLibrary("testcontainers-mysql").get())
    "testFixturesApi"(libs.findLibrary("testcontainers-core").get())
    "testFixturesApi"(libs.findLibrary("spring-boot-starter-test").get())
    "testFixturesApi"(libs.findLibrary("kotest-extensions-spring").get())
    "testFixturesApi"(libs.findLibrary("kotest-runner-junit5").get())

    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "testImplementation"(libs.findLibrary("kotest-extensions-spring").get())
    "testRuntimeOnly"(libs.findLibrary("mysql-connector").get())
}
