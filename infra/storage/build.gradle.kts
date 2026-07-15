// 스토리지 구현 어댑터 — :core 의 StorageObjectStore seam 을 AWS S3(HeadObject·DeleteObject)로 구현한다(KB-138).
// presigned URL 발급 구현(KB-145)도 이 모듈에 얹힌다. 조립은 부트앱 config 소관.
plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":core"))

    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.s3)

    "implementation"(libs.spring.context)
    "implementation"(libs.slf4j.api)
}
