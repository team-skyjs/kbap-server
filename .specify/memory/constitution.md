<!--
SYNC IMPACT REPORT
==================
Version change: 1.1.0 → 2.0.0   (개정 2026-06-27)
Bump rationale (MAJOR): 원칙 V(Domain Content Language Policy)의 규범을 하위호환 깨짐으로 재정의.
  - 기존: "음식 콘텐츠는 한국어·영어만 저장 / 그 외 언어 저장 금지"
  - 변경: "한국어(ko) 원문 + 9개 대상 언어 사전 번역 저장"(ADR-0003)
  - 명시적 금지("그 외 언어 저장하지 않는다")를 제거·역전하므로 MAJOR.
  영향: 원칙 V 본문 교체. 원칙 I~IV·Additional Constraints·Governance 의미 불변.
  비고: 이 개정으로 헌법 ↔ ADR-0003 상충(이전 분석 C1) 해소.

Modified principles:
  V. Domain Content Language Policy — 저장 언어 정책을 한·영 → ko 원문 + 9개 대상 언어로 재정의

Principles defined:
  I.   Test-First Development (NON-NEGOTIABLE)
  II.  Bounded Contexts — No Cross-Domain Coupling
  III. Layered Dependency Direction
  IV.  Persistence Encapsulation
  V.   Domain Content Language Policy (개정)

Added sections: 없음 · Removed sections: 없음

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check 게이트가 헌법을 동적 참조. 변경 불필요.
  ✅ .specify/templates/tasks-template.md — Test-First 동기화 상태 유지, 언어 정책 무관. 변경 불필요.
  ✅ .specify/templates/spec-template.md  — 헌법 결합 없음. 변경 불필요.

Follow-up: spec 001(menu-scan-mock)의 음식 상세를 9개국어(B-2: 대상 언어 파라미터 + ko 폴백)로 갱신 필요
  — spec.md/data-model.md/contracts/tasks.md 후속 편집 대상.
-->

# Meogo API Constitution

## Core Principles

### I. Test-First Development (NON-NEGOTIABLE)

TDD로 진행한다. 요구사항을 토대로 **실패하는 테스트를 우선 작성**하고, 이를 통과시키는 방향으로
구현하며 코드를 완성해 나간다. Red → Green → Refactor 사이클을 엄격히 따른다.

- 구현 코드보다 테스트를 먼저 작성하고, 작성 직후 **반드시 실패(Red)** 함을 확인한다.
- 테스트를 통과시키는 최소 구현(Green) 뒤 리팩터링(Refactor) 한다.
- 테스트 없는 구현은 머지 대상이 아니다. 테스트는 선택이 아니라 필수다.

Rationale: 요구사항을 실행 가능한 명세로 고정하고, 회귀를 방지하며, 설계를 테스트 가능한
단위로 유지하기 위함이다.

### II. Bounded Contexts — No Cross-Domain Coupling

도메인은 `meogo-api` 컨테이너 직속의 컨텍스트별 모듈(`:meogo-api:{food,member,scan,assessment,research}`)로 둔다(평탄화). (`research`는 미스 메뉴 조사·종합 파이프라인, 배치 전용 — ADR-0004.)

- **도메인 모듈은 서로 직접 의존하지 않는다.** 컨텍스트 조합은 오직 `:meogo-api:application`에서 한다.
- 다른 Aggregate·Context의 객체 전체를 직접 들지 않고 **ID·코드·스냅샷 값**으로 참조한다.
- Aggregate 내부 상태는 Aggregate Root를 통해서만 변경한다.

Rationale: 컨텍스트 독립성을 지켜 변경 파급을 막고, 추후 도메인별/Worker 분리를 쉽게 한다.

### III. Layered Dependency Direction

모듈 의존은 한 방향으로만 흐른다: `:meogo-api:presentation` → `:meogo-api:application` → 도메인 모듈.
배치 앱 `:meogo-batch`도 같은 방향으로 `:meogo-api:application`을 의존한다.

- `:meogo-api:core`는 도메인 커널로 모두가 의존 가능, `:meogo-api:infra`는 port/adapter로만 연결한다.
  `meogo-common`은 앱 간 공유 계약(통합 이벤트·DTO·기술 공통)으로 web/jpa/도메인에 의존하지 않는다.
- 모듈 간 project 의존은 **`implementation`을 기본**으로 한다. 공개 API에 타입이 드러나는
  의도적 노출에만 `api`를 쓴다.
- `:meogo-api:infra`는 조립 모듈(`:meogo-api:presentation`·`:meogo-batch`)이 런타임(`runtimeOnly`)에 주입한다.
  `:meogo-api:application`은 infra 구현체에 직접 의존하지 않는다(계층 역전 금지).

Rationale: 의존 역전을 막고, 상위 계층이 하위 구현 세부에 묶이지 않게 한다.

### IV. Persistence Encapsulation

JPA Entity / Mongo Document / Spring Data Repository / DomainRepository 구현체는
도메인 모듈 내부(`infrastructure`/`adapter` 패키지)에 숨긴다.

- `:meogo-api:application`·`:meogo-api:presentation`은 이들을 **import 하지 않는다.** 외부에는 Domain Entity와
  DomainRepository 인터페이스만 공개한다.
- 영속 기술 의존(`data-jpa`/`data-mongodb`)은 `implementation`으로 두어 상위 컴파일
  클래스패스에 노출되지 않게 한다(런타임 전이만 허용).
- 이 경계는 패키지 가시성 + 코드 리뷰 + **ArchUnit 테스트**로 강제한다.

Rationale: 도메인을 영속 기술로부터 보호하고, 기술 교체 시 도메인/유스케이스가 흔들리지 않게 한다.

### V. Domain Content Language Policy

음식 콘텐츠(음식명·설명·재료명·알러지/종교·비건 주의 성분)는 **한국어(`ko`) 원문 + 9개 대상 언어로
사전 번역해** DB에 저장한다([ADR-0003](../../docs/adr/0003-pretranslated-batch-menu-pipeline.md)).

- 9개 대상 언어: `zh-Hans`(중국어 간체)·`en`(영어)·`ja`(일본어)·`zh-Hant`(중국어 번체)·`vi`(베트남어)·
  `id`(인도네시아어)·`th`(태국어)·`ru`(러시아어)·`es`(스페인어).
- 번역은 `meogo-batch`가 LLM으로 생성하며, 알러지/식이 제한처럼 **안전 직결 데이터는 검수 상태를 구분**한다.
- 정적 UI 문구 번역 정책과 음식 콘텐츠 번역 정책은 **분리**한다(혼동 금지).
- `ko`는 번역 대상이 아니라 항상 존재하는 원문(source)이며, 미지원/미지정 언어 응답 시 `ko`로 폴백한다.

Rationale: 외국인 사용자에게 음식 안전 정보를 모국어로 제공하되(서비스 핵심 가치),
데이터 품질·일관성과 번역 책임 경계(콘텐츠 vs UI)를 유지한다.

## Additional Constraints (기술·아키텍처)

- 스택: Kotlin 2.3 / JDK 21 toolchain / Spring Boot 4.1, Gradle 멀티모듈(Kotlin DSL).
  영속: MySQL(+H2 test) + MongoDB, 마이그레이션 Flyway. LLM: Spring AI 2.0.
- 실행 bootJar 는 둘: `:meogo-api:presentation`(web, 진입점 `com.meogo.api.MeogoApiApplication`)와
  `:meogo-batch`(배치, 진입점 `com.meogo.batch.MeogoBatchApplication`). 공통 빌드 설정은
  `buildSrc` 컨벤션 플러그인(`meogo.*`)에 둔다.
- 외부 LLM 등 호출을 DB 트랜잭션 안에서 길게 잡지 않는다(스캔: pending 저장 → 외부 호출 →
  결과 저장 후 completed 전환).
- 도메인/영속 모델을 API 응답으로 그대로 노출하지 않는다.

> 구속력 없는 상세 "어떻게"(패키지 레이아웃·빌딩블록·컨텍스트별 개념)는 레퍼런스로
> [`docs/architecture/meogo-conventions.md`](../../docs/architecture/meogo-conventions.md) 및
> [`meogo-api-module-structure.md`](../../docs/architecture/meogo-api-module-structure.md)에 둔다.

## Development Workflow

- 기능 작업은 Spec Kit 흐름을 따른다: `/speckit-specify` → `/speckit-plan` → `/speckit-tasks`
  → `/speckit-implement` (필요 시 `/speckit-clarify`·`/speckit-analyze`·`/speckit-checklist`).
- `/speckit-plan`의 Constitution Check 게이트를 통과해야 다음 단계로 진행한다.
- 각 task는 **테스트 우선**으로 처리한다(원칙 I): 구현 전 실패 테스트를 작성·확인한다.
- 작업/논리 단위마다 커밋한다.

## Governance

본 헌법은 다른 모든 관행에 우선한다.

- 개정은 문서화된 변경(이 파일 수정 + Sync Impact Report 갱신)과 버전 증가를 동반한다.
- 버전 규칙(SemVer): MAJOR=원칙 제거/비호환 재정의, MINOR=원칙/섹션 추가·실질 확장,
  PATCH=문구·오타·비의미 보정.
- 모든 설계·PR은 본 헌법 준수를 검증한다. 위반은 정당화하거나 설계를 수정한다.
- 런타임 개발 가이드는 루트 [`CLAUDE.md`](../../CLAUDE.md), 상세 규범은
  [`docs/architecture/meogo-conventions.md`](../../docs/architecture/meogo-conventions.md)를 참조한다.

**Version**: 2.0.0 | **Ratified**: 2026-06-25 | **Last Amended**: 2026-06-27
