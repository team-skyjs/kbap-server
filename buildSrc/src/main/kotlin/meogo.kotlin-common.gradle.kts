import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// ───────── 전 모듈 공통 ─────────
// kotlin-jvm·java-library·Java 21 toolchain·Kotlin 엄격성 플래그·공통 테스트(Kotest+JUnit).
// Spring-free 모듈(core/common 등)도 이 플러그인만 적용한다.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

group = "com.meogo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion.toInt()

configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

configure<KotlinJvmProjectExtension> {
    compilerOptions {
        // -Xjsr305=strict: JSR-305 nullability 를 강제 제약으로 취급
        // -Xannotation-default-target=param-property: 프로퍼티 애너테이션 기본 타깃 변경
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
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
}
