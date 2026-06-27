# 0001. 멀티앱 모듈 레이아웃 (meogo-api 컨테이너 + batch + common)

- **상태**: Accepted <!-- web 모듈명(api→presentation)·패키지 규약은 ADR-0005에서 보완 -->
- **날짜**: 2026-06-26
- **관련**: [meogo-conventions](../architecture/meogo-conventions.md), [meogo-api-module-structure](../architecture/meogo-api-module-structure.md), 헌법 v1.1.0 (원칙 II·III), [ADR-0005](./0005-unified-api-package-and-presentation-rename.md)

## Context

초기 구조는 "실행 모듈은 `meogo-api` 하나"를 전제로 한 레이어 멀티모듈(`meogo-core`·`meogo-domain:*`·`meogo-application`·`meogo-infra`·`meogo-api`)이었다. 이후 다음 요구가 생겼다.

- **여러 실행 단위** — API 외에 배치(스케줄/잡), 그리고 추후 알림 컨슈머 같은 실행 앱을 한 레포에서 관리하고 싶다.
- **비즈니스 재사용** — batch 는 API 와 같은 `food`/`member` 등 도메인·유스케이스를 재사용한다. API 가 본진이고 batch 는 그 application 유스케이스를 트리거하는 위성 앱이다.
- **앱 간 공유 계약** — 통합 이벤트·DTO·로깅 설정처럼 여러 앱이 공유할 가벼운 계약이 필요하다.

제약: 도메인 자율성과 영속 캡슐화(헌법 IV)를 유지하고, MVP 단계에선 단순함을 우선한다. 깃은 아직 미초기화.

## Decision

레포를 **멀티앱 모노레포**로 둔다.

- **`meogo-api` = 컨테이너 폴더**(빌드 파일 없음). 그 안에 leaf 모듈을 **평탄하게** 둔다: `api`(web bootJar)·`application`·`infra`·`core` + 도메인 컨텍스트 `food`/`member`/`scan`/`assessment`(+ deferred `review`). `meogo-domain` 중첩 컨테이너는 제거하고 경로는 `:meogo-api:<leaf>`.
- **`meogo-batch`** = 별도 bootJar(단일 모듈). `:meogo-api:application`을 트리거하고 `:meogo-api:infra`를 `runtimeOnly`로 조립(컴파일 의존 X). flyway 는 끈다.
- **`meogo-common`** = 최상위 공유 모듈. 통합 이벤트·공통 DTO·기술 공통(logback 조각·유틸·횡단 어노테이션). **Spring-free**, web/jpa/도메인 의존 금지(가볍게 유지 → 디커플드 컨슈머도 안전).
- **영속은 per-domain 캡슐화 유지** — JPA Entity·Repository·adapter 는 각 도메인 모듈 내부에 숨긴다(중앙 `infra-persistence` 모듈을 두지 않는다). **Flyway 마이그레이션**은 스키마 owner 인 `:meogo-api:api`의 `src/main/resources/db/migration`에 모은다(전역 순서·단일 history).
- **이벤트 배치** — in-process 도메인 이벤트는 `:meogo-api:core`/도메인 모듈, 브로커를 타고 다른 앱이 받는 통합 이벤트는 `meogo-common`(평면 값만).

진입점: `:meogo-api:api` → `com.meogo.MeogoApiApplication`, `:meogo-batch` → `com.meogo.batch.MeogoBatchApplication`. 둘 다 `scanBasePackages = ["com.meogo"]`.

## Alternatives Considered

- **앱마다 독립 도메인(완전 분리)** — batch/notify 가 각자 도메인을 복제. 같은 비즈니스를 여러 번 구현하게 되어 비권장.
- **얇은 `apps/` + 공유 비즈니스 최상위** — application/domain/infra/core 를 최상위로 끌어올리고 api/batch/notify 를 얇게. 경계는 더 깔끔하나, 도메인을 `meogo-api` 안에 두는 지금 선택이 더 단순. 필요해지면 기계적 리네임으로 전환 가능(보류).
- **중앙 `infra-persistence:{mysql,mongo}` 모듈** — 도메인을 영속에서 완전히 떼어내 모듈 차원 순수성↑. 그러나 그 모듈이 여러 도메인에 의존하는 결합이 생기고 MVP 엔 과함. flyway 단일 홈은 `:meogo-api:api` 리소스로 충분히 달성되므로 채택 안 함(보류).
- **`notify` 컨슈머 지금 추가** — 브로커 미정이라 보류. `meogo-common`을 가볍게 유지해 추후 부착이 쉽도록 설계만 열어둠.

## Consequences

- **좋음**: API·배치가 같은 `application`·도메인을 재사용한다. 컨텍스트 경계가 Gradle 의존으로도 드러난다. `meogo-common`으로 디커플드 컨슈머 확장 여지를 연다. 도메인 영속 캡슐화가 그대로 유지된다(`:meogo-api:api` 컴파일 클래스패스에 도메인·JPA 비노출 — 검증 완료).
- **트레이드오프**: `:meogo-batch`가 `:meogo-api:application`에 의존해, 경로상 "batch 가 api 에 의존"처럼 읽힌다(실제론 api 폴더 밑 application 라이브러리). 거슬리면 공유 비즈니스를 최상위로 끌어올리는 리네임으로 해소.
- **후속/리스크**: ① 모듈 경계를 ArchUnit 으로 강제. ② `notify` 추가 시 `meogo-common`의 통합 이벤트 계약 확정. ③ 배치 잡이 늘면 `meogo-batch`를 목적별 모듈로 분리. ④ 브로커·JWT·배치 기술 등 미정 벤더는 도입 시 별도 결정.
