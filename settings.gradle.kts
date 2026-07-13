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

rootProject.name = "kbap-server"

include(
    // ── 공유 코어 + 도메인 컨텍스트 (영속 포함 — ADR-0012) ──
    ":core",       // 도메인 공유 커널 — 공통타입·외부 client seam·stereotype·영속 공통(BaseEntity)
    ":domain:food",
    ":domain:member",
    ":domain:avoidance",
    ":domain:research",
    ":domain:review",
    ":domain:scan",         // 스캔 이력 컨텍스트 (KB-111) — 최근 스캔 기록·조회

    // ── 유스케이스 계층 ──
    ":application", // 유스케이스 조율 — 도메인 서비스 조합·transaction boundary (진입점별 분할은 실제로 늘 때 재도입)

    // ── 인프라(driven 어댑터) ──
    ":infra:llm", // LLM 외부 연동 어댑터(Spring AI 3모델 fan-out) — 배치가 직접 의존

    // ── 부트앱 진입점 ──
    ":app:api",           // web bootJar (조립) — 진입점 com.kbap.api.KbapApiApplication
    ":app:batch",         // batch bootJar — flyway off

    // ── 공유 모듈 (app:api·app:batch 공유) ──
    ":common",
)
