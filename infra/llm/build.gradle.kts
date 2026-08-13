plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(libs.spring.ai.starter.openai)
}
