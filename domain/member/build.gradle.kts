// member 도메인 컨텍스트. 공통 설정은 컨벤션 플러그인에서 온다.
// refresh token 저장소(KB-118)가 이 컨텍스트 소유라 Redis 의존을 여기서 얹는다.
plugins {
    id("kbap.domain-conventions")
}

dependencies {
    "implementation"(libs.spring.boot.starter.data.redis)
}
