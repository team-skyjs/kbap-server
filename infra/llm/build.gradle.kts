plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(libs.spring.ai.starter.openai)
    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.bedrockruntime)
}
