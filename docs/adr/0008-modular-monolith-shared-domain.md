# ADR-0008: 모듈러 모놀리스 — 공유 도메인/영속, batch 직접 의존

- **상태**: Accepted (2026-06-29)
- **관련**: supersedes 의사결정 일부 — [ADR-0001](./0001-multi-app-modular-layout.md)(앱-모듈 배치), [ADR-0006](./0006-central-persistence-adapter-and-decoupled-batch.md)(디커플드 batch). [ADR-0003](./0003-pretranslated-batch-menu-pipeline.md)·[ADR-0004](./0004-research-bounded-context.md)의 "batch 는 통합 이벤트로만 소통" 전제를 완화. 트리거: `specs/004-avoidance-catalog`(알러지 공통 코드를 batch 가 공유해야 함).

## Context

기존 구조는 `meogo-api` 컨테이너 안에 core·도메인·application·infra·persistence·presentation 을 두고, `meogo-batch` 는 **meogo-api 무의존·통합 이벤트로만 소통하는 디커플드 위성 앱**으로 두었다(ADR-0001·0006).

그러나:
- 데이터 계층은 **이미 공유**였다 — batch 는 같은 MySQL 을 쓰되 `flyway off`(스키마 owner=api). 디커플은 *코드 레벨*에만 존재했다.
- 회피·주의 성분 공통 코드(`AvoidanceSubstance` 등)·도메인 모델을 batch(LLM 프롬프트·조사 종합)도 써야 한다. 이벤트로만 소통하면 같은 도메인/엔티티를 **두 번 정의**해야 하고, 안전 직결 데이터의 드리프트 위험이 생긴다.
- 멀티모듈의 목적(재사용·중복 제거)이 코드 레벨에서 살지 않았다.

## Decision

**모듈러 모놀리스로 전환한다.** 공유 계층(core·application·infra·persistence)을 최상위로 올리고, **두 부트앱(`app:api`·`app:batch`)이 필요한 모듈을 직접 의존**해 도메인/영속/외부 어댑터를 재사용한다.

### 최종 모듈 구조

```
common/                  기술 공통 · 통합 이벤트 (Spring-free, 도메인 무의존)
core/
  kernel/                도메인 공유 커널: 공통타입·port·RiskLevel·stereotype + 공유 코드(enum)
  food/ member/ scan/ avoidance/ research/ review/   도메인(ORM-free)
application/             유스케이스 · 트랜잭션 경계 (도메인 port 의존)
infra/
  persistence/           JPA 엔티티·Spring Data·Adapter (도메인 port 구현)
  (external/             LLM 등 외부 — 추후 LLM 착수 시 추가)
app/
  api/                   web bootJar — controller·DTO·BaseResponse, Flyway 스키마 owner
  batch/                 batch bootJar — 잡, flyway off, application 미의존
```

- 모듈명 **접두어(`meogo-`) 제거**, 그룹 컨테이너(`core/`·`infra/`·`app/`)로 분류(내부 앱이라 path·group 으로 네임스페이스 충분).
- 의존 방향(단방향): `core:kernel ← core:도메인 ← application`, `infra:persistence`·`infra:external`은 도메인/kernel port 구현, `app:api`·`app:batch` 가 `runtimeOnly` 로 조립. `common` 은 누구에게도 의존하지 않는 leaf.
- **batch 는 `core:도메인`(+필요 시)·`infra:*` 를 직접 의존**(통합 이벤트 전용 제약 폐기). application 은 당장 미의존(자기 잡에서 도메인 port 직접 사용).
- **스키마 단일 owner 유지** — `app:api` 만 Flyway, `app:batch` off. 두 앱이 **같은 `infra:persistence` 엔티티 공유** → 매핑 드리프트 차단(기존보다 안전).

## Consequences

**+**
- 도메인·엔티티·외부 어댑터 **중복 0**, 안전 직결 코드 단일 소스.
- type-safe 공통 코드가 api·batch 양쪽에서 동일.
- 멀티모듈 재사용 실효, 구조가 hexagonal(진입점=app, driven 어댑터=infra)과 정렬.

**−**
- 앱 간 결합 강화(독립 배포·장애 격리 약화). 단 DB 는 원래 공유라 손실 제한적.
- batch 가 공유 application 을 의존하게 되면 web 전용 빈 혼입 위험 → 당장은 application 미의존으로 회피, 추후 의존 시 컴포넌트 스캔 스코프 관리 필요.
- 대규모 이동(디렉터리·패키지·settings·컨벤션 플러그인·ArchUnit). 2단계(구조 이동 → 패키지 정리)로 위험 분산.

## 후속

- `meogo-conventions.md`·`CLAUDE.md`·ArchUnit 의존 규칙을 새 구조로 갱신.
- 패키지 네임스페이스는 `com.meogo.*` 유지(계층별 `com.meogo.{core,application,infra,api,batch,common}`).
- 통합 이벤트는 폐기하지 않고 *비동기 신호*가 필요한 경로에 선택적으로 유지.
