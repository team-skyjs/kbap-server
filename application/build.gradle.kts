plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":domain:scan"))
    "implementation"(project(":domain:review"))
    "implementation"(project(":common"))

    "implementation"(libs.spring.context)
    "implementation"(libs.spring.tx)
    "implementation"(libs.slf4j.api)
}
