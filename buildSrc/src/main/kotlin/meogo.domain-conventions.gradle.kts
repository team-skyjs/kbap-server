import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// ───────── 도메인 컨텍스트 모듈 공통 ─────────
// food/member/scan/avoidance/research/review 가 동일하게 갖는 설정을 한곳에 모은다(ADR-0012).
// 각 도메인 모듈 = 도메인 모델(불변·ORM-free) + 도메인 서비스(public 창구) + JPA 엔티티·Spring Data
// 리포지토리(internal). 영속이 도메인 모듈 안으로 들어오므로 spring·jpa 를 여기서 얹는다.
// - 엔티티·리포지토리는 Kotlin internal 로 감춘다 — 모듈 밖 접근은 컴파일러가 차단.
// - :core 는 도메인 공개 API 에 드러나므로 api() 로 전이 노출한다.
// - data-jpa 는 implementation — 상위(application·app) 컴파일 클래스패스로 새지 않는다.
// - 통합 테스트는 MySQL Testcontainers 공통 설정(:core testFixtures)을 쓴다.
plugins {
    id("meogo.kotlin-common")
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
    "implementation"(libs.findLibrary("spring-boot-starter-data-jpa").get())
    "implementation"(libs.findLibrary("kotlin-reflect").get())
    "implementation"(libs.findLibrary("jackson-module-kotlin").get())
    "runtimeOnly"(libs.findLibrary("mysql-connector").get())

    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "testImplementation"(libs.findLibrary("kotest-extensions-spring").get())
    "testImplementation"(testFixtures(project(":core")))
    "testRuntimeOnly"(libs.findLibrary("mysql-connector").get())
}
