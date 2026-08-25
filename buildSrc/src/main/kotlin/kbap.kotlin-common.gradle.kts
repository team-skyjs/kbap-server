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
    // Kotest 발견 단계가 classgraph 로 테스트 클래스패스 전체를 스캔한다 — 워커 기본 힙(512m)으로는
    // api 모듈에서 OutOfMemoryError("failed to discover tests")가 난다(CI 에서 먼저 드러남).
    maxHeapSize = "2g"
    System.getProperty("kotest.tags")?.let { systemProperty("kotest.tags", it) }
}
