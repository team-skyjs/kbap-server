import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// ───────── 도메인 컨텍스트 모듈 공통 ─────────
// food/member/scan/avoidance/research/review 가 동일하게 갖는 설정을 한곳에 모은다.
// 각 도메인 모듈 = JPA 엔티티(도메인 메서드 내장) + Spring Data 리포지토리 + 도메인 정책 클래스.
// application 이 리포지토리·엔티티를 직접 다루므로 data-jpa 는 api 로 전이 노출한다.
// - :core 는 도메인 공개 API 에 드러나므로 api() 로 전이 노출한다.
// - 통합 테스트는 MySQL Testcontainers 공통 설정(:core testFixtures)을 쓴다.
plugins {
    id("kbap.kotlin-common")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("io.spring.dependency-management")
}

// Hibernate 프록시 경고(HHH000305) 방지: kotlin-spring 이 깔아둔 allopen 에 JPA 애너테이션을 추가해
// 엔티티 클래스·게터를 open 으로 만든다(가시성 internal 은 불변 — 경계 유지).
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
    "api"(project(":core"))
    "api"(libs.findLibrary("spring-boot-starter-data-jpa").get())
    "implementation"(libs.findLibrary("kotlin-reflect").get())
    "implementation"(libs.findLibrary("jackson-module-kotlin").get())
    "runtimeOnly"(libs.findLibrary("mysql-connector").get())

    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "testImplementation"(libs.findLibrary("kotest-extensions-spring").get())
    "testImplementation"(testFixtures(project(":core")))
    "testRuntimeOnly"(libs.findLibrary("mysql-connector").get())
}
