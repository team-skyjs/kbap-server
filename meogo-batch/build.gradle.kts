plugins {
    // 배치 bootJar. 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("meogo.spring-boot-application")
}

dependencies {
    // 배치는 meogo-api 내부 모듈(application/infra/persistence/도메인)에 일절 의존하지 않는다.
    // 디커플드 위성 앱 — meogo-api 와는 :meogo-common 의 통합 이벤트(브로커)로만 소통한다.
    "implementation"(project(":meogo-common"))

    "implementation"(libs.spring.boot.starter)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
