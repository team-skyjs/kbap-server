plugins {
    // 배치 bootJar. 공통 설정은 buildSrc 컨벤션 플러그인에서 온다.
    id("meogo.spring-boot-application")
}

dependencies {
    // 배치는 api 의 application 유스케이스를 트리거한다.
    "implementation"(project(":meogo-api:application"))
    "implementation"(project(":meogo-common"))

    // application 의 port 구현(adapter)을 런타임에 조립한다(컴파일 의존 X) — api 와 동일한 조립 책임.
    // (영속/도메인은 application 을 통해 런타임 전이되므로 별도 선언 불필요.)
    "runtimeOnly"(project(":meogo-api:infra"))

    "implementation"(libs.spring.boot.starter)

    "testRuntimeOnly"(libs.h2)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
