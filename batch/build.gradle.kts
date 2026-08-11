plugins {
    id("kbap.spring-boot-application")
}

dependencies {
    "implementation"(project(":infra:llm"))

    "implementation"(project(":common"))

    "implementation"(project(":infra:storage"))
    "implementation"(project(":infra:mq"))
    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.sqs)

    "implementation"(libs.spring.boot.starter)

    "implementation"("org.springframework.boot:spring-boot-starter-batch")

    "testImplementation"(testFixtures(project(":common")))
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
