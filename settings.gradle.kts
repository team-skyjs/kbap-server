pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kbap-server"

include(
    ":common",
    ":domain:review",
    ":domain:scan",
    ":domain:bookmark",
    ":domain:image",
    ":domain:metering",

    ":application",

    ":infra:llm",
    ":infra:auth",
    ":infra:redis",
    ":infra:storage",

    ":app:api",
    ":app:batch",
)
