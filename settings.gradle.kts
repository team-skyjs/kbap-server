pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the Java 21 toolchain regardless of the local JAVA_HOME.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "meogo-server"

include(
    // ── meogo-api 앱 (컨테이너 meogo-api 안의 leaf 모듈들) ──
    ":meogo-api:presentation", // web bootJar (조립) — 진입점 com.meogo.api.MeogoApiApplication
    ":meogo-api:application",  // 유스케이스 조율
    ":meogo-api:infra",        // 외부 연동(LLM 등) adapter
    ":meogo-api:core",         // 도메인 커널 (Spring-free)
    // 도메인 컨텍스트 (평탄화 — meogo-domain 컨테이너 없이 meogo-api 직속)
    ":meogo-api:food",
    ":meogo-api:member",
    ":meogo-api:scan",
    ":meogo-api:assessment",
    ":meogo-api:research",
    ":meogo-api:review",

    // ── meogo-batch 앱 ──
    ":meogo-batch",

    // ── 공유 모듈 (meogo-api·meogo-batch 공유) ──
    ":meogo-common",
)
