import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kbap.kotlin-common")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("io.spring.dependency-management")
    `java-test-fixtures`
}

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
