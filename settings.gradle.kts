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
    // ── 공유 코어 + 도메인 컨텍스트 (엔티티·레포지토리·도메인 서비스, 단방향 상호 의존 허용) ──
    ":core",       // 도메인 공유 커널 — 공통타입·외부 client seam·영속 공통(BaseEntity)·통합 ErrorCode/예외
    ":domain:food",
    ":domain:member",
    ":domain:avoidance",
    ":domain:research",
    ":domain:review",
    ":domain:scan",         // 스캔 이력 컨텍스트 (KB-111) — 최근 스캔 기록·조회

    // ── 조합 계층 ──
    ":application", // 무소속 유스케이스(Home·Auth)와 도메인 간 순환 해소용 ~ApplicationService 만 둔다

    // ── 인프라(driven 어댑터) ──
    ":infra:llm", // LLM 외부 연동 어댑터(Spring AI 3모델 fan-out) — 배치가 직접 의존
    ":infra:auth", // 인증 구현 어댑터(jjwt 자체 JWT + firebase-admin 소셜 검증)

    // ── 부트앱 진입점 ──
    ":app:api",           // web bootJar (조립) — 진입점 com.kbap.api.KbapApiApplication
    ":app:batch",         // batch bootJar — flyway off
)
