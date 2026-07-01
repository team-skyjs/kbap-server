<!--
SYNC IMPACT REPORT
==================
Version change: 2.1.0 → 2.2.0   (개정 2026-07-02)
Bump rationale (MINOR): 원칙 V 의 "고정 reference taxonomy 예외" 단서를 현행 구조로 재서술했다.
  007 리팩터(이슈 #21)로 회피·주의 성분 카탈로그의 데이터(ko 원문·9개 번역·분류)를 enum 에서
  DB 단일 출처로 옮기고 enum 은 데이터 없는 식별자(AvoidanceSubstanceCode)만 남겼으므로, 기존
  전제("고정 taxonomy 는 DB 대신 공유 컴파일 enum 으로 데이터를 보유할 수 있다")를 "콘텐츠 데이터=DB 단일
  출처 + 식별자(코드) enum(콘텐츠 미보유, 개발 가독성용 한국어 label 만 허용·시드 정합 검증)"로 현행화한다.
  원칙의 목적(ko+9 번역·ko 폴백·콘텐츠↔UI 분리·안전 직결 정합)과 강제력은 불변이라 MAJOR 아님(제거/비호환
  재정의 없음). 실질 가이드 재서술이라 PATCH 아님.

Modified principles:
  V.   Domain Content Language Policy — "고정 reference taxonomy 예외" 재서술:
       컴파일 enum 데이터 저장 허용 → 콘텐츠 데이터 DB 단일 출처 + 식별자(코드) enum 은 콘텐츠 미보유
       (개발 가독성용 label 만 허용, label=시드 korean_name 정합).

Added sections: 없음 · Removed sections: 없음

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check 게이트가 헌법을 동적 참조. 변경 불필요.
  ✅ .specify/templates/tasks-template.md — Test-First 동기화 유지, 무관. 변경 불필요.
  ✅ .specify/templates/spec-template.md  — 헌법 결합 없음. 변경 불필요.

Docs propagation: 원칙 V 예외 문구는 constitution.md 외 참조처 없음(grep 확인). 별도 문서 동기화 불필요.

Follow-up: 없음(이 개정으로 이슈 #21 의 "원칙 V 예외 단서 조정" 항목 완료).
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

도메인은 `core/` 컨테이너 직속의 컨텍스트별 모듈(`:core:{food,member,scan,avoidance,research}`,
deferred placeholder `:core:review`)로 둔다(ADR-0008). (`research`는 미스 메뉴 조사·종합 파이프라인,
배치 전용 — ADR-0004. `avoidance`는 회피·주의 성분 카탈로그와 판정을 소유하는 컨텍스트 — 구 `assessment`.)

- **도메인 모듈은 서로 직접 의존하지 않는다.** 컨텍스트 조합은 오직 `:application:*`(현재 `:application:client`)에서 한다.
- 다른 Aggregate·Context의 객체 전체를 직접 들지 않고 **ID·코드·스냅샷 값**으로 참조한다.
  (예: member·food 는 회피·주의 성분을 `avoidance` 의 enum 을 import 하지 않고 코드로 참조한다.)
- Aggregate 내부 상태는 Aggregate Root를 통해서만 변경한다.

Rationale: 컨텍스트 독립성을 지켜 변경 파급을 막고, 추후 도메인별/Worker 분리를 쉽게 한다.

### III. Layered Dependency Direction

모듈 의존은 한 방향으로만 흐른다: 부트앱(`:app:api`·`:app:batch`) → `:application:*` → 도메인 모듈(`:core:*`)
→ `:core:kernel`. 두 부트앱은 공유 계층을 직접 의존해 도메인/영속을 재사용한다(ADR-0008).

- `:core:kernel`은 도메인 커널로 모두가 의존 가능하며 **완전 Spring-free**다. `:infra:persistence`는
  도메인 port 를 구현하는 영속 어댑터로, 부트앱이 `runtimeOnly`로 조립한다(상위 컴파일 의존 X).
  `:common`은 앱 간 공유 계약(통합 이벤트·기술 공통)으로 web/jpa/도메인에 의존하지 않는다.
- 모듈 간 project 의존은 **`implementation`을 기본**으로 한다. 공개 API에 타입이 드러나는
  의도적 노출에만 `api`를 쓴다(도메인 모듈 → `api(:core:kernel)`).
- `:application:*`은 외부 어댑터 구현체에 직접 의존하지 않고 **port 인터페이스로만** 사용한다(계층 역전 금지).

Rationale: 의존 역전을 막고, 상위 계층이 하위 구현 세부에 묶이지 않게 한다.

### IV. Persistence Encapsulation

JPA Entity / Spring Data Repository / DomainRepository 구현체는 **`:infra:persistence` 모듈에 모은다**
(ADR-0008 — 각 도메인을 `implementation`으로 의존해 port 를 구현). 도메인 모듈은 ORM-free 로 둔다.

- `:application:*`·`:app:api`는 이들을 **import 하지 않는다.** 외부에는 Domain 객체와
  DomainRepository(port) 인터페이스만 공개한다.
- 영속 기술 의존(`data-jpa`/`data-mongodb`)은 `implementation`으로 두어 상위 컴파일
  클래스패스에 노출되지 않게 한다(런타임 전이만 허용).
- 이 경계는 패키지 가시성 + 코드 리뷰 + **ArchUnit 테스트**(`app/api` 의 `ModuleBoundaryTest`)로 강제한다.

Rationale: 도메인을 영속 기술로부터 보호하고, 기술 교체 시 도메인/유스케이스가 흔들리지 않게 한다.

### V. Domain Content Language Policy

음식 콘텐츠(음식명·설명·재료명·알러지/종교·비건 주의 성분)는 **한국어(`ko`) 원문 + 9개 대상 언어로
사전 번역해** DB에 저장한다([ADR-0003](../../docs/adr/0003-pretranslated-batch-menu-pipeline.md)).

- 9개 대상 언어: `zh-Hans`(중국어 간체)·`en`(영어)·`ja`(일본어)·`zh-Hant`(중국어 번체)·`vi`(베트남어)·
  `id`(인도네시아어)·`th`(태국어)·`ru`(러시아어)·`es`(스페인어).
- 번역은 `:app:batch`가 LLM으로 생성하며, 알러지/식이 제한처럼 **안전 직결 데이터는 검수 상태를 구분**한다.
- 정적 UI 문구 번역 정책과 음식 콘텐츠 번역 정책은 **분리**한다(혼동 금지).
- `ko`는 번역 대상이 아니라 항상 존재하는 원문(source)이며, 미지원/미지정 언어 응답 시 `ko`로 폴백한다.
- **고정 reference taxonomy — 식별자 enum + DB 단일 출처**: 운영자 런타임 편집이 없고 고정·읽기 전용인
  기준 목록(예: 회피·주의 성분 카탈로그 — 81종)은 **데이터(ko 원문·9개 대상 언어 번역·분류)를 DB 단일 출처**로
  두고(소프트삭제·ko 폴백 포함), **컴파일 타입 안전을 위한 식별자(코드) enum**(예: `AvoidanceSubstanceCode`)만
  소유 컨텍스트 모듈에 둔다 — 이 enum 은 코드 상수 + **개발자 가독성용 한국어 `label`**(런타임 미사용·비권위,
  코드 옆에서 성분을 알아보게 하는 힌트)만 가지며, 사용자 노출 표시명·9개 번역·분류 등 **콘텐츠 데이터는 이지
  않는다**(콘텐츠는 DB 단일 출처). 식별자 enum 은 컴파일타임 코드 집합·망라 매칭(`when`)·타 컨텍스트의 타입 안전
  참조에 쓰고, **시드 정합**(시드/DB 코드 집합 = enum 코드 집합, 그리고 `label` = 시드 korean_name)으로 드리프트를
  배포 전 차단한다. ko 원문 + 9개 번역·ko 폴백·콘텐츠↔UI 분리는 동일하게 충족한다.
  근거: 데이터 단일 출처(DB)로 redundancy·데이터 누수를 없애고, 식별자 enum 으로 앱 간 컴파일타임 정합을 유지한다
  (안전 직결) — ADR-0008·spec 004·007(#21). (동적 메뉴 콘텐츠는 본 예외 대상이 아니다.)

Rationale: 외국인 사용자에게 음식 안전 정보를 모국어로 제공하되(서비스 핵심 가치),
데이터 품질·일관성과 번역 책임 경계(콘텐츠 vs UI)를 유지한다.

## Additional Constraints (기술·아키텍처)

- 스택: Kotlin 2.3 / JDK 21 toolchain / Spring Boot 4.1, Gradle 멀티모듈(Kotlin DSL) — 모듈러 모놀리스(ADR-0008).
  영속: MySQL(+H2 test) + MongoDB, 마이그레이션 Flyway. LLM: Spring AI 2.0.
- 실행 bootJar 는 둘: `:app:api`(web, 진입점 `com.meogo.MeogoApiApplication` — 패키지 루트라 전 계층 스캔)와
  `:app:batch`(배치, 진입점 `com.meogo.app.batch.MeogoBatchApplication`). 공통 빌드 설정은
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

**Version**: 2.2.0 | **Ratified**: 2026-06-25 | **Last Amended**: 2026-07-02
