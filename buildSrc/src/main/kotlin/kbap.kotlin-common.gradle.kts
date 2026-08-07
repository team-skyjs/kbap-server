import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    jacoco
}

group = "com.kbap"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion.toInt()

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

configure<KotlinJvmProjectExtension> {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-Xemit-jvm-type-annotations",
        )
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("kotest-runner-junit5").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    System.getProperty("kotest.tags")?.let { systemProperty("kotest.tags", it) }
    // 스모크 스펙(@EnabledIf)은 테스트 JVM 의 시스템 프로퍼티를 읽는다 — Gradle CLI -D 값을 전달.
    System.getProperties().stringPropertyNames()
        .filter { it.endsWith(".smoke.enabled") }
        .forEach { systemProperty(it, System.getProperty(it)) }
}
