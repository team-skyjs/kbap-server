// scan 도메인 컨텍스트(스캔 이력). 공통 설정(core 의존 + Spring/ORM-free)은 컨벤션 플러그인에서 온다.
plugins {
    id("kbap.domain-conventions")
}

dependencies {
    "api"(project(":domain:food"))   // 메뉴 매칭·미완성 등록
    "api"(project(":domain:member")) // 기피 성분·스캔 카운트
    "implementation"(project(":domain:image")) // 스캔 전 업로드 이미지 검증·소유 확인
}
