// 인증 구현 어댑터 — application 의 TokenIssuer/TokenParser·Social* seam 을
// jjwt(자체 JWT)·firebase-admin(소셜 검증/삭제)으로 구현한다. 조립은 부트앱 config 소관.
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
