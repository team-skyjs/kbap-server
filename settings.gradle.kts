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
    // ── 공유 코어 + 도메인 컨텍스트 (영속 포함 — ADR-0012) ──
    ":core",       // 도메인 공유 커널 — 공통타입·외부 client seam·stereotype·영속 공통(BaseEntity)
    ":domain:food",
    ":domain:member",
    ":domain:avoidance",
    ":domain:research",
    ":domain:review",
    ":domain:scan",         // 스캔 이력 컨텍스트 (KB-111) — 최근 스캔 기록·조회

    // ── 유스케이스 계층 (진입점별 분할 — 교차 도메인 공유는 추후 :application:shared) ──
    ":application:client", // 사용자 API 유스케이스 (현재 유일 — batch/admin/shared 는 생길 때 추가)

    // ── 인프라(driven 어댑터) ──
    ":infra:llm", // LLM 외부 연동 어댑터(Spring AI 3모델 fan-out) — 배치가 직접 의존

    // ── 부트앱 진입점 ──
    ":app:api",           // web bootJar (조립) — 진입점 com.meogo.api.MeogoApiApplication
    ":app:batch",         // batch bootJar — flyway off

    // ── 공유 모듈 (app:api·app:batch 공유) ──
    ":common",
)
