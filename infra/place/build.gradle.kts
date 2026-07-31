plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(libs.spring.web)
    "implementation"(libs.spring.context)
    "implementation"(libs.slf4j.api)
}
