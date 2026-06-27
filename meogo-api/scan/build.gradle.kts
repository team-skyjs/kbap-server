// scan 도메인 컨텍스트. 공통 설정(core 의존 + jpa/mongo 은닉 + mysql/h2 + kotlin-jpa no-arg)은 컨벤션 플러그인에서 온다.
plugins {
    id("meogo.domain-conventions")
}
