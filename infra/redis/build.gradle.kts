plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))
    "implementation"(libs.spring.boot.starter.data.redis)
    "implementation"(libs.spring.context)

    "testImplementation"(testFixtures(project(":common")))
}
