import java.time.Duration
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
    // Kotest 는 시작 시 classgraph 로 테스트 클래스패스 전체 jar 를 스캔해 AbstractProjectConfig·@AutoScan 을 찾는다.
    // 이 레포는 둘 다 쓰지 않는데, prometheus 클라이언트(protobuf 쉐이딩) jar 추가 후 CI 워커 기본 힙(512m)에서
    // 스캔 중 OOM 으로 디스커버리가 깨졌다(KB-380). 스캔을 끄고 힙도 여유를 둔다. 스펙 탐지는 Gradle 의 ClassSelector 로 충분하다.
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
    systemProperty("kotest.framework.discovery.jar.scan.disable", "true")
    maxHeapSize = "1g"
    // 테스트가 끝나지 않는 대기(교착·반환하지 않는 워커)에 걸려도 빌드가 CI 외부 타임아웃까지 멈추지 않게 하는 안전망.
    // Kotest 의 kotest.framework.timeout 은 코루틴 취소라 Future.get()·latch.await() 로 블록된 스레드를 끊지 못한다(실측).
    // 가장 느린 모듈(api)이 CI 에서 5~6분이라 3배 이상 여유를 둔다.
    timeout.set(Duration.ofMinutes(20))
}
