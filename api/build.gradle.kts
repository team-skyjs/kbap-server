plugins {
    id("kbap.spring-boot-application")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(libs.firebase.admin)
    "implementation"(libs.jjwt.api)
    "runtimeOnly"(libs.jjwt.impl)
    "runtimeOnly"(libs.jjwt.jackson)

    "implementation"(libs.spring.boot.starter.data.redis)

    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.s3)

    "implementation"("tools.jackson.module:jackson-module-kotlin")


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
    "testImplementation"(libs.jjwt.impl)
    "testImplementation"(libs.jjwt.jackson)
    "testRuntimeOnly"(libs.mysql.connector)

    "testImplementation"(libs.spring.boot.webmvc.test)

    "testImplementation"(libs.archunit)

}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
