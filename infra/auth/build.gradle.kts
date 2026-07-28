plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(libs.firebase.admin)
    "implementation"(libs.jjwt.api)
    "runtimeOnly"(libs.jjwt.impl)
    "runtimeOnly"(libs.jjwt.jackson)

    "implementation"(libs.spring.context)
    "implementation"(libs.slf4j.api)

    "testImplementation"(libs.jjwt.impl)
    "testImplementation"(libs.jjwt.jackson)
}
