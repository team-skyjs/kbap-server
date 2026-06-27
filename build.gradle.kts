// 루트 빌드 — 집계 전용. 실제 공통 빌드 설정은 buildSrc 의 컨벤션 플러그인에 있다:
//   meogo.kotlin-common          — 전 모듈 공통(kotlin/toolchain/엄격성/테스트)
//   meogo.spring-conventions     — Spring 라이브러리 공통(core/common 제외)
//   meogo.spring-boot-application— 부트 앱(bootJar): :meogo-api:api, :meogo-batch
//   meogo.domain-conventions     — 도메인 5종 공통
//
// 각 모듈은 plugins { id("meogo.<archetype>") } 로 적용한다.
// 라이브러리/플러그인 버전 단일 출처: gradle/libs.versions.toml.
