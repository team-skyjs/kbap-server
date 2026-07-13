// Redis 어댑터 — application 의 RefreshTokenStore seam 을 Redis(TTL 자동 만료)로 구현한다.
plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":application"))
    "implementation"(libs.spring.boot.starter.data.redis)
    "implementation"(libs.spring.context)

    "testImplementation"(testFixtures(project(":core")))
}
