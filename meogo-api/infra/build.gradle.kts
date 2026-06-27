plugins {
    id("meogo.spring-conventions")
}

dependencies {
    "implementation"(project(":meogo-api:core"))
    // 통합 이벤트 등 공유 계약을 발행할 때 common 의 메시지 클래스를 쓴다.
    "implementation"(project(":meogo-common"))

    // LLM client (Spring AI). 3개 모델 병렬 호출:
    //  - OpenAI            → openai 스타터
    //  - Upstage(Solar)    → OpenAI 호환이라 openai 스타터 재사용(코드에서 base-url 교체)
    //  - Google Gemini     → google-genai 스타터 (Spring AI 2.x 통합 Google GenAI SDK)
    "implementation"(libs.spring.ai.starter.openai)
    "implementation"(libs.spring.ai.starter.google.genai)
}
