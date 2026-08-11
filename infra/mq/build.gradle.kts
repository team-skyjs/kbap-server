plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.sqs)

    "implementation"(libs.spring.context)
    "implementation"(libs.slf4j.api)
}
