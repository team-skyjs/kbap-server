plugins {
    id("meogo.spring-conventions")
}

dependencies {
    "implementation"(libs.spring.ai.starter.openai)
    "implementation"(libs.spring.ai.starter.google.genai)
}
