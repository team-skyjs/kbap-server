plugins {
    id("kbap.common-conventions")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

dependencies {
    "implementation"(libs.spring.ai.starter.openai)
    "implementation"("org.springframework.ai:spring-ai-retry")
    // 텍스트·비전 caller(번역·메뉴 추정·재료 판정·비전·리뷰 봇)는 Claude 공식 SDK. OpenAI 는 이미지 생성·임베딩·되돌림 스위치용으로 남는다.
    "implementation"(libs.anthropic.java)

    // 벡터 저장소 어댑터가 S3VectorsClient 를 공개 시그니처로 노출하고, 부트앱 config 가 클라이언트를 조립한다.
    "api"(platform(libs.aws.bom))
    "api"(libs.aws.s3vectors)
    "implementation"(libs.aws.sqs)
    "implementation"(libs.spring.web)
    "implementation"("tools.jackson.module:jackson-module-kotlin")
}
