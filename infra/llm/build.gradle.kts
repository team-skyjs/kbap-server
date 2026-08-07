plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(libs.spring.ai.starter.openai)
    "implementation"(libs.spring.ai.starter.google.genai)
    "implementation"(libs.spring.ai.starter.bedrock)
}
