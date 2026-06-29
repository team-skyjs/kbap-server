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
    // ── 공유 코어 (도메인 커널 + 컨텍스트, ORM-free) ──
    ":core:kernel",       // 도메인 공유 커널 (Spring-free) — 공통타입·port·stereotype·공유 코드
    ":core:food",
    ":core:member",
    ":core:scan",
    ":core:assessment",
    ":core:research",
    ":core:review",

    // ── 유스케이스 계층 (진입점별 분할 — 교차 도메인 공유는 추후 :application:shared) ──
    ":application:client", // 사용자 API 유스케이스 (현재 유일 — batch/admin/shared 는 생길 때 추가)

    // ── 인프라(driven 어댑터) ──
    ":infra:persistence", // 영속 adapter — ORM(JPA)·BaseEntity, 도메인 port 구현
    // ":infra:external" — LLM 등 외부 연동, LLM 착수 시 추가

    // ── 부트앱 진입점 ──
    ":app:api",           // web bootJar (조립) — 진입점 com.meogo.api.MeogoApiApplication
    ":app:batch",         // batch bootJar — flyway off

    // ── 공유 모듈 (app:api·app:batch 공유) ──
    ":common",
)
