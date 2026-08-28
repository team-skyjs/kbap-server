plugins {
    id("kbap.spring-boot-application")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.sqs)

    "implementation"("org.springframework.boot:spring-boot-starter-batch")
    "implementation"(libs.spring.boot.starter.web)
    "implementation"(libs.spring.boot.starter.actuator)
    "runtimeOnly"(libs.micrometer.registry.prometheus)

    "testImplementation"(testFixtures(project(":common")))
    "testImplementation"(libs.spring.boot.webmvc.test)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
