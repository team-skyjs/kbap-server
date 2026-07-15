// 스토리지 구현 어댑터 — application 의 PresignedUploadPort seam 을 AWS S3(s3-presigner)로 구현한다.
// presign 은 로컬 SigV4 서명이라 발급 시 S3 를 호출하지 않는다. 조립은 부트앱 config 소관.
// presigner 클래스는 s3 아티팩트에 포함돼 별도 s3-presigner 의존이 필요 없다.
plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":application"))
    "implementation"(project(":core"))

    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.s3)

    // @ConditionalOnProperty·@ConditionalOnMissingBean 등 Boot 조건 애너테이션(spring-boot-autoconfigure).
    "implementation"(libs.spring.boot.starter)
    "implementation"(libs.spring.context)
    "implementation"(libs.slf4j.api)

    "testImplementation"(platform(libs.aws.bom))
    "testImplementation"(libs.aws.s3)
}
