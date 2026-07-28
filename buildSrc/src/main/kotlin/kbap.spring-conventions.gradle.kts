import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kbap.kotlin-common")
    id("org.jetbrains.kotlin.plugin.spring")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val springBootVersion = libs.findVersion("spring-boot").get().requiredVersion
val springAiVersion = libs.findVersion("spring-ai").get().requiredVersion

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

dependencies {
    "implementation"(libs.findLibrary("kotlin-reflect").get())
    "implementation"(libs.findLibrary("jackson-module-kotlin").get())
    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "testImplementation"(libs.findLibrary("kotest-extensions-spring").get())
}
