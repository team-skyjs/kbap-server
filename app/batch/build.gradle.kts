plugins {
    // 배치 bootJar. 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("kbap.spring-boot-application")
}

dependencies {
    // ADR-0008/0010: 배치는 필요한 도메인/infra 모듈을 직접 의존해 같은 도메인/DB/어댑터를 재사용한다.
    // 현재 직접 의존: :infra:llm (Spring AI 기반 LLM fan-out 어댑터 — 잡에서 호출).
    "implementation"(project(":infra:llm"))

    // KB-182 콘텐츠 파이프라인: food 도메인 창구(FoodContentBatchService)를 @Import 조립해 음식 단위 처리.
    "implementation"(project(":domain:food"))
    // 기피성분 매핑 스텝(KB-209)이 회피 카탈로그를 조회할 자리 — 골격 단계에선 미참조.
    "implementation"(project(":domain:avoidance"))

    // 사진 생성 결과를 오브젝트 스토리지에 저장(StorageObjectStore 조립은 BatchStorageConfig).
    "implementation"(project(":infra:storage"))

    "implementation"(libs.spring.boot.starter)

    // KB-182: 콘텐츠 파이프라인을 Spring Batch(chunk-oriented Step)로 구동한다.
    // 메타데이터 테이블(BATCH_*)은 스키마 owner=api 의 Flyway 가 생성한다(배치는 flyway off).
    "implementation"("org.springframework.boot:spring-boot-starter-batch")

    // 배치는 도메인 모듈(영속 포함)을 직접 의존하므로 부팅 시 JPA/데이터소스가 필요하다(ADR-0012).
    // 통합 부팅 검증(@SpringBootTest)은 api 와 동일하게 MySQL Testcontainers 공통 설정(:core testFixtures)을 쓴다(KB-46 동등성).
    "testImplementation"(testFixtures(project(":common")))
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
