# 0005. meogo-api 패키지 규약 통일 (`com.meogo.api.<모듈명>`) + web 모듈 `api`→`presentation` 리네임

- **상태**: Accepted
- **날짜**: 2026-06-27
- **관련**: specs/001-menu-scan-mock, ADR-0001

## Context

ADR-0001에서 `meogo-api` 컨테이너 아래 leaf 모듈을 평탄화했으나, 패키지 규약은 모듈별로 갈려 있었다:

- web 모듈(`:meogo-api:api`) → `com.meogo.api.*`
- 도메인 컨텍스트(`:meogo-api:{scan,food,...}`) → `com.meogo.domain.<context>`
- 계층(core/application/infra) → `com.meogo.core` / `com.meogo.application` / `com.meogo.infra`
- web 진입점 `MeogoApiApplication` → `com.meogo`

`meogo-api` 하위 모든 모듈이 같은 컨테이너에 속하는데 패키지 루트가 제각각이라 일관성이 떨어지고, 모듈-패키지 매핑을 한눈에 보기 어려웠다. "모든 하위 모듈은 `com.meogo.api.<모듈명>`"으로 통일하자는 요구가 나왔다.

단, 단순 통일에는 충돌이 있다 — web 모듈명이 `api`라서 `com.meogo.api.<모듈명>`을 적용하면 `com.meogo.api.api`가 되고, web이 이미 쓰던 `com.meogo.api.scan`(컨트롤러)이 도메인 `scan`의 새 패키지 `com.meogo.api.scan`과 **split package 충돌**을 일으킨다.

## Decision

**`meogo-api` 하위 모든 모듈의 패키지 루트를 `com.meogo.api.<모듈명>`으로 통일한다.** 충돌을 피하기 위해 web 모듈을 리네임한다.

- web 모듈 `:meogo-api:api` → **`:meogo-api:presentation`** (디렉터리 `meogo-api/api/` → `meogo-api/presentation/`).
- 패키지 매핑:
  - 커널: `com.meogo.api.core`
  - 계층: `com.meogo.api.application`, `com.meogo.api.infra`
  - 도메인 컨텍스트: `com.meogo.api.<context>`(예: `com.meogo.api.scan`, `com.meogo.api.food`) — 영속 구현은 그 아래 `infrastructure`/`adapter`.
  - web: `com.meogo.api.presentation`(컨트롤러·DTO·`ApiResponse`·예외 핸들러).
- **부트 진입점 `MeogoApiApplication`은 `com.meogo.api`** 패키지에 두고 `@SpringBootApplication(scanBasePackages = ["com.meogo.api"])` — 전 하위 패키지를 컴포넌트/엔티티/리포지토리 스캔.
- `meogo-batch`는 변경 없음(`com.meogo.batch`, `scanBasePackages = ["com.meogo"]` 유지 — `com.meogo.api.*` 의존을 포함하는 부모 스캔). 모듈 의존 방향(ADR-0001)·영속 캡슐화(헌법 IV)는 의미 불변.

이는 ADR-0001의 모듈 레이아웃 결정을 유지하되 **web 모듈 이름과 패키지 규약만** 갱신한다(ADR-0001을 supersede하지 않고 보완).

## Alternatives Considered

- **web을 `com.meogo.api.api`로 두고 모듈명 유지** — 규칙엔 일관되나 `api.api`가 어색하고, `:meogo-api:api`(컨테이너+모듈 중복)도 그대로. 기각.
- **web은 `com.meogo.api`에 두고 도메인만 통일** — web feature 패키지(`com.meogo.api.scan`)와 도메인(`com.meogo.api.scan`)이 split package 충돌. 기각.
- **현행 유지(`com.meogo.domain.<context>`)** — 통일 요구를 충족하지 못함.

## Consequences

- **좋음**: `meogo-api` 모듈↔패키지가 `com.meogo.api.<모듈명>`으로 1:1 매핑돼 탐색·일관성↑. web 모듈명 `presentation`이 역할(웹 표현 계층)을 더 잘 드러낸다.
- **트레이드오프**: 기존 코드/문서의 패키지·모듈 경로를 일괄 이동(US1 구현분 + CLAUDE.md·conventions·module-structure·헌법·specs). 1회성 리팩터로 처리하고 `./gradlew test`로 검증함.
- **후속/리스크**: 이 경계(모듈↔패키지, web↔도메인 분리)는 추후 **ArchUnit**으로 강제한다(현재는 패키지 가시성+리뷰). `meogo-batch`의 `scanBasePackages`는 `["com.meogo"]`로 충분하나, 향후 `com.meogo.*` 형제 패키지가 늘면 `["com.meogo.api","com.meogo.batch"]`로 좁히는 것을 검토.
