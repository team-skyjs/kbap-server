plugins {
    jacoco
    id("jacoco-report-aggregation")
}

repositories {
    mavenCentral()
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencies {
    jacocoAggregation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    jacocoAggregation(platform("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}"))

    jacocoAggregation(project(":common"))
    jacocoAggregation(project(":api"))
    jacocoAggregation(project(":batch"))
}

reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
}

tasks.named<JacocoReport>("testCodeCoverageReport") {
    val originalClassDirs = classDirectories.files.toList()
    classDirectories.setFrom(
        originalClassDirs.map {
            fileTree(it) {
                exclude(
                    "**/KbapApiApplication*",
                    "**/KbapBatchApplication*",
                    "**/*JpaEntity*",
                )
            }
        },
    )
}
