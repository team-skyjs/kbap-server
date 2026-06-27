import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// ───────── Spring 라이브러리 공통 (core/common 제외) ─────────
// kotlin-common 위에 kotlin-spring·dependency-management·Boot/AI BOM·공통 Spring 의존을 얹는다.
plugins {
    id("meogo.kotlin-common")
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
