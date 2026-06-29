// ───────── 실행 가능한 부트 앱 (bootJar) ─────────
// Spring 라이브러리 공통 위에 spring-boot 플러그인을 얹어 bootJar 를 만든다.
// 사용처: :app:api (web), :app:batch.
plugins {
    id("meogo.spring-conventions")
    id("org.springframework.boot")
}
