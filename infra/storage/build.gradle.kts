// 스토리지 구현 어댑터 — :core 의 StorageObjectStore seam(KB-138, HeadObject·DeleteObject)과
// :application 의 PresignedUploadPort seam(KB-145, presigned PUT)을 AWS S3 로 구현한다.
// presign 은 로컬 SigV4 서명이라 발급 시 S3 를 호출하지 않는다. 조립은 부트앱 config 소관.
plugins {
    id("kbap.spring-conventions")
}

dependencies {
    "implementation"(project(":common"))

    "implementation"(platform(libs.aws.bom))
    "implementation"(libs.aws.s3)

    "implementation"(libs.spring.context)
    "implementation"(libs.slf4j.api)
}
