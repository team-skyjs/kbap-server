# 0016. 모듈 다이어트 — 애플리케이션 모듈을 api·batch·common 3개로 통합하고 경계 강제를 ArchUnit 으로 이관

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: specs/kb-244-module-diet, ADR-0008, ADR-0012, ADR-0014

## Context

ADR-0012 이후 구조는 도메인 컨텍스트마다 Gradle 모듈을 두는 모듈러 모놀리스였다. 도메인이 8종(food·member·avoidance·scan·bookmark·image·metering + review placeholder)까지 늘며 모듈은 16개가 됐고, 그 비용이 실이득을 넘어섰다:

- 새 코드마다 "어느 모듈인가 + 어떤 의존 선언이 필요한가"를 결정해야 했고, 빌드 파일·컨벤션 플러그인 관리 지점이 모듈 수만큼 늘었다.
- Gradle 모듈 경계가 주던 것(도메인 간 순환의 컴파일 차단)은 이미 패키지 기준 ArchUnit 규칙으로 동등하게 강제할 수 있다 — 실제로 전이 클래스패스 누수(scan·bookmark 가 선언 없이 avoidance 를 참조)는 Gradle 이 잡지 못했고 ArchUnit 도입 시점에 발견됐다.
- 반면 외부 시스템 어댑터(infra 4종 — llm·auth·redis·storage)의 모듈 격리는 SDK 의존 오염 방지라는 실이득이 있다.

## Decision

애플리케이션 모듈을 **`:common`·`:app:api`·`:app:batch` 3개**로 통합하고, **`:infra:{llm,auth,redis,storage}` 4종은 유지**한다(총 7모듈 + buildSrc).

- **배치 기준 하나**: "api 밖(배치 **또는** 인프라 어댑터)이 컴파일 의존하는가" — 그렇다면 `:common`(커널·food·member·avoidance·외부 시스템 seam 인터페이스), 아니면 `:app:api`(컨트롤러·조합 계층·scan·bookmark·image·metering).
- **패키지가 소속을 드러낸다**: common 소속 코드는 `com.kbap.common.{core, domain.<ctx>, application.<영역>}`, api 전용 도메인은 `com.kbap.domain.<ctx>` 유지.
- **도메인 경계 강제는 ArchUnit 단독**(`ModuleBoundaryTest`): 도메인 간 허용 방향 맵(단일 출처)·계층 의존 방향·엔티티 위치·커널 Spring-free 를 패키지 기준으로 검증한다. 모듈 수준 강제는 common↔api↔batch↔infra 간 Gradle 의존으로만 남는다.
- seam 인터페이스(TokenIssuer·TokenParser·SocialTokenVerifier·RefreshTokenStore·PresignedUploadPort)는 `:common` 에 둔다 — infra 가 참조하는 타입이 api 에 있으면 api↔infra 순환이 생긴다.

## Alternatives Considered

- **현행 유지(도메인별 모듈)** — 관리 비용의 원인이 그대로 남는다.
- **문자 그대로 3모듈(인프라까지 흡수)** — AWS·Firebase·Spring AI SDK 가 common/api 컴파일 클래스패스에 오염되고, seam 패턴(인터페이스·구현·조립 분리)의 조립 지점이 흐려진다.
- **도메인 8종 전부 common** — batch 가 쓰지 않는 도메인까지 공유돼 "batch 가 무엇을 쓰는지"가 모듈 경계에서 다시 안 보이게 된다.

## Consequences

- 새 코드 배치 결정이 "공유 여부" 하나로 끝나고, 애플리케이션 빌드 파일이 3개로 준다.
- 도메인 간 순환 차단이 컴파일 타임(Gradle)에서 테스트 타임(ArchUnit)으로 늦춰진다 — `ModuleBoundaryTest` 의 허용 맵이 방향의 단일 출처가 되므로 리뷰에서 맵 변경을 의식적으로 다룬다.
- 도메인 테스트가 부트앱 테스트 환경(Flyway 실스키마)을 공유하게 된다 — Hibernate 생성 스키마에는 없던 FK 제약을 테스트 시드가 만족해야 한다(별도 TestApp 컨텍스트는 루트 스캔과 빈이 충돌해 폐기).
- 헌법 원칙 II·III·IV 의 "컨텍스트별 모듈" 문언을 재정의하는 개정(v6.0.0)이 동반된다. ADR-0012 의 모듈 구성 결정은 본 ADR 로 대체된다(seam·엔티티=도메인 모델 등 나머지 결정은 유지).
