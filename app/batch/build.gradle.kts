plugins {
    // 배치 bootJar. 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("meogo.spring-boot-application")
}

dependencies {
    // ADR-0008/0010: 배치는 필요한 도메인/infra 모듈을 직접 의존해 같은 도메인/DB/어댑터를 재사용한다.
    // (더 이상 :common 통합 이벤트 전용 디커플 위성 앱이 아니다.)
    // 현재 직접 의존: :infra:llm (Spring AI 기반 LLM fan-out 어댑터 — 잡에서 호출).
    "implementation"(project(":common"))
    "implementation"(project(":infra:llm"))

    // KB-53 스코어링 조율: research 도메인 로직 + food/avoidance 입력 수집 + persistence 어댑터 런타임 조립.
    "implementation"(project(":core:research"))
    "implementation"(project(":core:food"))
    "implementation"(project(":core:avoidance"))
    "runtimeOnly"(project(":infra:persistence"))

    "implementation"(libs.spring.boot.starter)

    // 배치도 :infra:persistence 를 runtimeOnly 로 조립하므로 부팅 시 JPA/데이터소스가 필요하다.
    // 통합 부팅 검증(@SpringBootTest)은 api 와 동일하게 MySQL Testcontainers 공통 설정(persistence testFixtures)을 쓴다(KB-46 동등성).
    "testImplementation"(testFixtures(project(":infra:persistence")))
    // 승격 배치 e2e 는 완성 candidate 픽스처를 JpaRepository 로 직접 insert 한다(US1 은 US2 컬럼-스코프 update 포트와 독립).
    // spring-data-jpa 가 :infra:persistence 에선 implementation 이라 배치 테스트 컴파일 클래스패스로 전이되지 않으므로 테스트 전용으로 얹는다.
    "testImplementation"(libs.spring.boot.starter.data.jpa)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
