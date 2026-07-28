plugins {
    jacoco
    // 멀티모듈 커버리지 집계: 각 모듈(kbap.kotlin-common 이 jacoco 적용)의 .exec 를 모아 단일 리포트로 만든다.
    id("jacoco-report-aggregation")
}

// 루트 빌드 — 집계 전용. 실제 공통 빌드 설정은 buildSrc 의 컨벤션 플러그인에 있다:
//   kbap.kotlin-common          — 전 모듈 공통(kotlin/toolchain/엄격성/테스트/jacoco)
//   kbap.spring-conventions     — Spring 라이브러리 공통(core/common 제외)
//   kbap.spring-boot-application— 부트 앱(bootJar): :app:api, :app:batch
//   kbap.domain-conventions     — 도메인 5종 공통
//
// 각 모듈은 plugins { id("kbap.<archetype>") } 로 적용한다.
// 라이브러리/플러그인 버전 단일 출처: gradle/libs.versions.toml.

repositories {
    mavenCentral()
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencies {
    // 모듈들이 BOM 관리 버전(예: spring-tx, spring-boot-starter-web)을 버전 없이 선언하므로,
    // io.spring.dependency-management 가 적용 안 되는 루트 집계 설정엔 BOM 을 platform 으로 직접 공급한다.
    jacocoAggregation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    jacocoAggregation(platform("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}"))

    jacocoAggregation(project(":common"))
    jacocoAggregation(project(":application"))
    jacocoAggregation(project(":app:api"))
    jacocoAggregation(project(":domain:food"))
    jacocoAggregation(project(":domain:member"))
    jacocoAggregation(project(":domain:review"))
    jacocoAggregation(project(":domain:scan"))
    jacocoAggregation(project(":infra:llm"))
    jacocoAggregation(project(":app:batch"))
}

reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
}

// 비즈니스 로직 커버리지를 왜곡하는 대상은 집계에서 제외한다(부트 진입점·JPA 엔티티).
// 현재 classDirectories 를 먼저 스냅샷(.files)으로 캡처해 setFrom 자기참조 순환을 끊는다.
tasks.named<JacocoReport>("testCodeCoverageReport") {
    val originalClassDirs = classDirectories.files.toList()
    classDirectories.setFrom(
        originalClassDirs.map {
            fileTree(it) {
                exclude(
                    "**/KbapApiApplication*",
                    "**/KbapBatchApplication*",
                    "**/*JpaEntity*",
                )
            }
        },
    )
}
