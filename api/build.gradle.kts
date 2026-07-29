plugins {
    id("kbap.spring-boot-application")
}

dependencies {
    "implementation"(project(":common"))

    "runtimeOnly"(project(":infra:llm"))

    "implementation"(project(":infra:auth"))

    "runtimeOnly"(project(":infra:redis"))

    "implementation"(project(":infra:storage"))


    "implementation"(libs.shedlock.spring)
    "implementation"(libs.shedlock.provider.jdbc.template)

    "implementation"(libs.spring.boot.starter.web)
    "implementation"(libs.spring.boot.starter.validation)
    "implementation"(libs.spring.boot.starter.actuator)
    "implementation"(libs.spring.boot.starter.thymeleaf)
    "implementation"(libs.spring.security.crypto)
    "implementation"(libs.springdoc.openapi.starter.webmvc.ui)

    "implementation"(libs.spring.boot.flyway)
    "implementation"(libs.flyway.core)
    "runtimeOnly"(libs.flyway.mysql)

    "testImplementation"(testFixtures(project(":common")))
    "testRuntimeOnly"(libs.mysql.connector)

    "testImplementation"(libs.spring.boot.webmvc.test)

    "testImplementation"(libs.archunit)

}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
