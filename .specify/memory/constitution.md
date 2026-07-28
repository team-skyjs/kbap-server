<!--
SYNC IMPACT REPORT
==================
Version change: 5.0.0 → 6.0.0   (개정 2026-07-28)
Bump rationale (MAJOR): 원칙 II·III·IV 의 **모듈 구성 문언을 비호환 재정의**한다(KB-244, ADR-0016).
  도메인 컨텍스트별 Gradle 모듈(:domain:*)·:core·:application 모듈을 해체하고 애플리케이션 모듈을
  **:common·:api·:batch 3개**로 통합한다(외부 시스템 어댑터 :infra:* 4종은 유지 — 총 7모듈).
  바운디드 컨텍스트·의존 방향·영속 소유의 **취지는 전부 유지**되고 실현 수단만 바뀐다: 컨텍스트 경계는
  모듈이 아니라 **패키지**가 긋는다 — 영속(엔티티·리포지토리)은 컨텍스트 불문 com.kbap.common.domain.<ctx>,
  도메인 서비스·dto 는 소비 앱 모듈(공유 com.kbap.common.domain.<ctx> / api 전용 com.kbap.domain.<ctx>).
  경계 강제는 Gradle 컴파일 차단에서 **ArchUnit(ModuleBoundaryTest — 도메인 간 허용 방향 맵 단일 출처)**
  로 이관한다. 함께: 원칙 II 의 "도메인 모듈은 서로 직접 의존하지 않는다"(v5 까지 잔존한 구문)를 실제
  구조(2026-07-14 이후 도메인 간 단방향 의존 허용)와 정합화하고, 원칙 III 의 구 `:common`(공유 계약,
  jpa 비의존) 서술을 새 :common(공유 커널+도메인+seam) 정의로 대체한다.

Modified principles:
  II. Bounded Contexts — 컨텍스트별 모듈 → 컨텍스트별 패키지. 도메인 간 단방향 의존 허용(순환 금지,
      ArchUnit 허용 맵) 정합화. 공유 vocabulary 의 :core 배치 → :common(com.kbap.common.core).
  III. Layered Dependency Direction — 모듈 그래프(부트앱→application→도메인→core) → 부트앱·infra→:common.
      패키지 수준 방향(app→application→domain→common.core)은 ArchUnit 이 동일하게 강제.
  IV. Persistence Ownership — "소유 도메인 모듈 안에" → "소유 도메인 패키지 안에". 경계 강제 수단 서술 갱신.

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check 가 헌법을 동적 참조. 변경 불필요.
  ✅ .specify/templates/tasks-template.md — 무관. 변경 불필요.
  ✅ .specify/templates/spec-template.md  — 헌법 결합 없음. 변경 불필요.

Docs propagation: ADR-0016(ADR-0012 의 모듈 구성 결정 대체) · CLAUDE.md 모듈 구조·컨벤션 절 ·
  docs/architecture/{meogo-conventions,meogo-api-module-structure}.md · specs/kb-244-module-diet/.

Follow-up: 없음(KB-244 구현과 동시 반영).

---
이전 개정 이력
Version change: 4.0.0 → 5.0.0   (개정 2026-07-22)
Bump rationale (MAJOR): 원칙 IV 를 **비호환 재정의**한다(KB-220). KB-134 가 확립한 "엔티티·Spring Data
  리포지토리를 Kotlin `internal` 로 감추고 도메인 서비스를 유일 공개 창구로 둔다"를 폐기한다 — 이 캡슐화는
  배치처럼 리포지토리가 필요한 소비 계층마다 위임 전용 창구 서비스(FoodContentBatchService·
  AvoidanceCatalogService)를 만들게 했고, 그 결과 소비 계층 성격의 데이터 접근 코드가 도메인 모듈에 섞였다.
  엔티티·리포지토리를 public 으로 두고 소비 계층이 직접 참조한다. 유지되는 것: 도메인 로직의 도메인 서비스
  소유, JPA 연관관계 금지(참조는 id 값), Flyway 스키마 owner(=api), 명시적 트랜잭션 경계.
  함께: 원칙 IV 의 "도메인 모델·엔티티 분리(toDomain/from 변환)" 서술을 실제 코드(2026-07-14 대개편 —
  엔티티=도메인 모델)와 정합화하고, 원칙 명칭을 Persistence Encapsulation → Persistence Ownership 으로 바꾼다.

Modified principles:
  III. Layered Dependency Direction — "도메인 서비스 유일 공개 창구" 조항 완화(리포지토리도 공개 API).
  IV. Persistence Encapsulation → Persistence Ownership — internal 캡슐화 폐기·소비 계층 직접 참조 허용·
      엔티티=도메인 모델 정합화·트랜잭션 경계 소유 조항 추가.

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check 가 헌법을 동적 참조. 변경 불필요.
  ✅ .specify/templates/tasks-template.md — 무관. 변경 불필요.
  ✅ .specify/templates/spec-template.md  — 헌법 결합 없음. 변경 불필요.

Docs propagation: ADR-0014(ADR-0012 의 internal 캡슐화 결정 대체) · docs/architecture/meogo-conventions.md ·
  docs/architecture/meogo-api-module-structure.md · CLAUDE.md · specs/kb-220-remove-internal/.

Follow-up: 없음(KB-220 구현과 동시 반영).

---
이전 개정 이력
Version change: 3.0.1 → 4.0.0   (개정 2026-07-20)
Bump rationale (MAJOR): 원칙 V 의 **언어 폴백 정책을 비호환 재정의**한다(KB-201). spec 008/이슈 #18 이
  고정했던 "지원 목록에 없는 코드 → fail-fast 400 + 지원 언어 목록 안내"를 **"영어(en) 폴백"** 으로 바꾸고,
  대신 "미지정 → ko 기본"을 **"비어 있으면 400 거절(lang 필수)"** 로 바꾼다. 즉 **거절 지점이 '값이 틀렸을 때'
  에서 '값을 안 채웠을 때'로 이동**한다. 근거: lang 은 사용자가 고르는 값이 아니라 기기 설정에서 흘러드는 값이라
  서비스가 통제할 수 없고, 미지원 기기 언어 사용자에게 400 을 주면 화면(특히 홈 = 진입 화면)이 열리지 않는다.
  함께: 외부 입력 유효성 검증을 요청 경계(컨트롤러 요청 DTO)가 소유하고 도메인·애플리케이션 서비스는 확정값을
  받는다는 조항을 원칙 V 에 추가. 그 결과 `LanguageCode.from` 이 순수 lookup 으로 축소되고
  `ErrorCode.UNSUPPORTED_LANGUAGE`(COMMON-001)가 삭제됐다.

Modified principles:
  V. Domain Content Language Policy — 폴백 3분기 재정의(비어 있음→400 / 번역 부재→ko / 미지원 코드→en)
     + 검증 소유 계층(요청 경계) 조항 추가. "정확 일치·관대한 정규화 금지"는 유지(trim 도 하지 않음으로 강화).

Supersedes: specs/008-unsupported-language-error/ (미지원 코드 400 결정)

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check 가 헌법을 동적 참조. 변경 불필요.
  ✅ .specify/templates/tasks-template.md — 무관. 변경 불필요.
  ✅ .specify/templates/spec-template.md  — 헌법 결합 없음. 변경 불필요.

Docs propagation: ADR-0013 · docs/architecture/meogo-conventions.md "언어 / 데이터 정책" ·
  specs/kb-201-home-lang-param/ · specs/008-unsupported-language-error/(superseded 표기).

Follow-up: 없음(KB-201 구현과 동시 반영).

Version change: 3.0.0 → 3.0.1   (개정 2026-07-13)
Bump rationale (MAJOR): 원칙 III·IV 를 **재정의**한다(비호환 재정의 = MAJOR). KB-134 에서 클린아키텍처
  ports & adapters 를 폐기했다 — 도메인 하나를 다루는 데 모델·port·엔티티·리포지토리·어댑터 다섯 조각이
  두 모듈에 흩어지는 비용이, 얻는 것(영속 기술 교체 가능성)보다 크다는 판단이다. `:infra:persistence` 를
  해체해 영속 코드를 **소유 도메인 모듈 안으로** 옮기고, 리포지토리 port 를 폐기하며, 각 도메인은
  **도메인 서비스 하나를 public 창구**로 두고 엔티티·Spring Data 리포지토리를 **Kotlin `internal`** 로 감춘다.
  Gradle 모듈이 컴파일 단위이므로 경계 강제 수단이 "리뷰 + ArchUnit" 에서 **컴파일러 + ArchUnit** 으로
  오히려 강해진다. 캡슐화 목적(원칙 IV)은 유지되고 수단만 바뀐다. 함께: JPA 연관관계 전면 금지(참조는 id 값),
  모듈 리네임(core/→domain/, 구 kernel 모듈→:core), MongoDB 스택 제거.

Modified principles:
  III. Layered Dependency Direction — port-only·runtimeOnly 조립 → 도메인 서비스 창구·직접 의존.
  IV. Persistence Encapsulation — ":infra:persistence 에 집결" → "소유 도메인 모듈 안에 internal 로".

3.0.1 (PATCH, 같은 날): :application:client 하위 모듈을 :application 단일 모듈로 평탄화(진입점별
  분할은 실제 필요 시 재도입) — 원칙 II·III 의 괄호 표기만 동기화, 원칙 본문·의미 불변.

Modified sections:
  II. Bounded Contexts — 모듈 표기(:core:*→:domain:*)·공유 id 값 클래스의 :core 배치 반영(원칙 취지 불변).
  Additional Constraints — MongoDB 제거, 모듈 구조 서술 갱신.

Added sections: 없음 · Removed sections: 없음

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check 게이트가 헌법을 동적 참조. 변경 불필요.
  ✅ .specify/templates/tasks-template.md — Test-First 동기화 유지, 무관. 변경 불필요.
  ✅ .specify/templates/spec-template.md  — 헌법 결합 없음. 변경 불필요.

Docs propagation: ADR-0012(ADR-0006·0008 supersede) · CLAUDE.md 모듈 구조·컨벤션 절 ·
  docs/architecture/{kbap-conventions,kbap-api-module-structure}.md · specs/kb-134-architecture-simplification/.

Follow-up: 없음(KB-134 구현과 동시 반영).
-->

# Kbap API Constitution

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

도메인은 컨텍스트별 **패키지**로 둔다(ADR-0016 — 컨텍스트별 Gradle 모듈은 KB-244 에서 해체):
**영속(JPA 엔티티·리포지토리)은 컨텍스트 불문 `com.kbap.common.domain.<ctx>`(`:common`)**,
도메인 서비스·dto 는 그 컨텍스트를 쓰는 앱 모듈에 둔다 — 공유 도메인(web·배치 공용)은
`com.kbap.common.domain.{food,member,avoidance}`(`:common`), api 전용 도메인은
`com.kbap.domain.{scan,bookmark,image,metering}`(`:api`). 한 컨텍스트가 두 패키지에 걸쳐도
ArchUnit 은 이를 같은 컨텍스트로 묶어 방향을 검사한다.

- **도메인 간 의존은 단방향만 허용하고 순환을 금지한다.** 허용 방향의 단일 출처는
  `ModuleBoundaryTest` 의 도메인 간 허용 맵(ArchUnit)이다 — 방향 추가·변경은 이 맵 수정으로만 하며
  리뷰에서 의식적으로 다룬다.
- 다른 Aggregate·Context의 객체 전체를 직접 들지 않고 **ID·코드·스냅샷 값**으로 참조한다.
  (컴파일 타입 안전이 필요한 식별자 enum — 예: `AvoidanceSubstanceCode` — 참조는 허용 맵의 단방향 의존.)
  여러 컨텍스트가 공유하는 vocabulary(`LanguageCode` 등)는 소유 도메인이 아니라
  **공유 커널(`com.kbap.common.core`)** 에 둔다 — 소유 도메인에 두면 참조하는 쪽에 도메인 간 의존이 생긴다.
- Aggregate 내부 상태는 Aggregate Root를 통해서만 변경한다.

Rationale: 컨텍스트 독립성을 지켜 변경 파급을 막고, 추후 도메인별/Worker 분리를 쉽게 한다.
경계의 실현 수단(모듈→패키지)이 바뀌어도 이 취지는 불변이다.

### III. Layered Dependency Direction

**모듈 의존**은 한 방향으로만 흐른다: 부트앱(`:api`·`:batch`)과 인프라 어댑터(`:infra:*`)가
**`:common`** 을 의존한다(ADR-0016). api 와 batch 는 서로를 모르고, `:common` 은 어떤 모듈도 의존하지
않는다. **패키지 의존**은 `com.kbap.{api,batch}` → `com.kbap.application` → 도메인 패키지 → `com.kbap.common.core`
방향을 유지하며 ArchUnit(`ModuleBoundaryTest`)이 강제한다.

- `:common` 은 **공유 커널(`com.kbap.common.core`) + 공유 도메인 + 외부 시스템 seam 인터페이스**다 —
  배치 기준은 "api 밖(배치 또는 인프라 어댑터)이 컴파일 의존하는가" 하나다. 커널(`common.core`)은
  **Spring-free** 를 유지한다(영속 공통 `BaseEntity`·`EntityStatus` 의 jakarta 애너테이션만 예외).
- 모듈 간 project 의존은 **`implementation`을 기본**으로 한다. 공개 API에 타입이 드러나는
  의도적 노출에만 `api`를 쓴다(`:common` → `api`(data-jpa) — 엔티티가 서비스 시그니처에 노출).
- 각 도메인 패키지의 공개 API 는 **도메인 서비스(비즈니스 로직 소유)·도메인 모델·리포지토리**다(KB-220 —
  리포지토리 `internal` 캡슐화 폐기, 원칙 IV). 소비 계층은 도메인 로직이 필요하면 도메인 서비스를,
  단순 영속 접근이면 리포지토리를 직접 쓴다.
  외부 시스템 클라이언트(LLM·소셜 인증·스토리지 등)는 **seam 인터페이스로만** 사용한다(계층 역전 금지) —
  인터페이스는 `:common`(`common.core`·`common.application`)에, 구현은 `:infra:*` 에, 조립은 부트앱 config 에 둔다.

Rationale: 의존 역전을 막고, 상위 계층이 하위 구현 세부에 묶이지 않게 하되, 도메인 하나를 다루는 데 필요한
조각 수를 최소로 유지한다(ADR-0012·0016).

### IV. Persistence Ownership

JPA Entity / Spring Data Repository 는 **그 데이터를 소유하는 도메인 패키지(`com.kbap.common.domain.<ctx>`
— 영속은 컨텍스트 불문 `:common`) 안에 public 으로 둔다**
(KB-220 — Kotlin `internal` 캡슐화 폐기, ADR-0014 가 ADR-0012 의 internal 결정을 대체). 소비 계층
(조합 계층·부트앱)은 단순 영속 접근이 필요하면 리포지토리·엔티티를 직접 참조한다 —
리포지토리 위임 외 로직이 없는 **창구 서비스를 만들지 않는다**.

- **엔티티가 곧 도메인 모델이다**(2026-07-14 대개편) — 도메인 메서드를 엔티티에 두고, 별도 도메인 모델
  클래스·`toDomain`/`from` 변환을 만들지 않는다.
- **도메인 로직(검증·상태 전이·정책·유비쿼터스 언어 행위)은 도메인 서비스가 소유한다.** 리포지토리 직접
  참조는 위임뿐인 중간 계층을 없애기 위한 것이지, 비즈니스 로직을 소비 계층으로 옮기는 허가가 아니다.
- **트랜잭션 경계는 사용하는 쪽이 명시적으로 소유한다.** 서비스 public 메서드는 명시적 `@Transactional`,
  소비 계층이 리포지토리를 직접 쓸 때도 필요한 경계를 스스로 선언한다(예: 배치 진행 저장의
  `TransactionTemplate(REQUIRES_NEW)` — 청크 실패에도 진행 커밋 유지).
- **엔티티 간 JPA 연관관계(`@OneToMany`·`@ManyToOne`·`@OneToOne`·`@ManyToMany`)를 두지 않는다.**
  참조는 **id 값**으로만 들고, 연관 데이터는 id(목록)로 명시 조회한다(예외: 읽기 전용 연관 — conventions
  문서 참조). 외래키 제약은 코드가 아니라 **Flyway 스키마**가 강제한다(스키마 owner = api Flyway).
- 경계는 **모듈 간 Gradle 의존 방향**(common/api/batch/infra) + **ArchUnit 테스트**(`api` 의
  `ModuleBoundaryTest` — 도메인 간 허용 방향 맵·도메인→상위 계층 금지·`@Entity` 위치·도메인 모델
  ORM-free)로 강제한다(ADR-0016 — 도메인 간 경계는 ArchUnit 단독).

Rationale: internal 캡슐화가 강제한 "도메인 서비스 유일 창구"는 리포지토리가 필요한 소비 계층마다 위임
전용 창구 서비스를 만들게 했고, 도메인 모듈에 소비 계층 성격의 코드가 섞였다(KB-220). 컴파일러 강제를
포기하는 대신 조각 수를 줄이고, 도메인 로직의 소유는 규율과 리뷰로 지킨다.

### V. Domain Content Language Policy

음식 콘텐츠(음식명·설명·재료명·알러지/종교·비건 주의 성분)는 **한국어(`ko`) 원문 + 9개 대상 언어로
사전 번역해** DB에 저장한다([ADR-0003](../../docs/adr/0003-pretranslated-batch-menu-pipeline.md)).

- 9개 대상 언어: `zh-Hans`(중국어 간체)·`en`(영어)·`ja`(일본어)·`zh-Hant`(중국어 번체)·`vi`(베트남어)·
  `id`(인도네시아어)·`th`(태국어)·`ru`(러시아어)·`es`(스페인어).
- 번역은 `:batch`가 LLM으로 생성하며, 알러지/식이 제한처럼 **안전 직결 데이터는 검수 상태를 구분**한다.
- 정적 UI 문구 번역 정책과 음식 콘텐츠 번역 정책은 **분리**한다(혼동 금지).
- `ko`는 번역 대상이 아니라 항상 존재하는 원문(source)이다. 표시 언어(`lang`)는 다음 세 경우로 구분한다
  (KB-201·[ADR-0013](../../docs/adr/0013-lang-english-fallback.md) — spec 008/이슈 #18 의 fail-fast 결정을 대체):
  (1) **비어 있음**(파라미터 누락·빈 문자열·공백) → 기본값으로 넘어가지 않고 **HTTP 400 으로 거절**한다.
  표시 언어를 받는 API 는 `lang`을 **필수**로 두며 기본값을 두지 않는다. (2) **지원 언어이나 번역 부재**(예: `ja`
  요청인데 해당 콘텐츠 일본어 번역 미보유) → `ko`로 폴백한다. (3) **지원 목록에 없는 코드**(값이 존재하고 지원 코드와
  정확히 일치하지 않음 — 대소문자·지역 변형·앞뒤 공백 포함, 예: `fr`·`EN`·`ko-KR`·`" ko "`) → 거절하지 않고
  **`en`(영어)으로 폴백**한다. 매칭은 **정확 일치**로 하고 관대한 정규화(trim·대소문자 보정)를 하지 않는다.
  근거: `lang`은 사용자가 고르는 값이 아니라 **기기 설정에서 흘러드는 값**이라 서비스가 통제할 수 없다. 지원하지
  않는 기기 언어(예: `fr`) 사용자에게 400을 주면 화면이 열리지 않으며, 홈처럼 진입 화면이면 앱 자체가 열리지 않는다.
  외국인 대상 서비스에서 영어 폴백은 한국어 폴백과 달리 상당수 사용자가 읽을 수 있어 조용한 폴백의 해악이 작다.
  **감수하는 비용**: 클라이언트의 코드 오타(`jp`)·대소문자 오류(`EN`)가 200 영어로 조용히 나가 QA 에서 드러나지
  않을 수 있다. (1)의 필수화가 이에 대한 부분적 방어다 — "값을 안 보낸 실수"만큼은 시끄럽게 실패한다.
- **유효성 검증은 요청 경계(컨트롤러)가 소유한다.** 외부 입력의 필수 여부·빈 값 판정은 web 계층의 요청 DTO
  (`@field:NotBlank` 등)가 처리하고, 도메인·애플리케이션 서비스는 **확정된 값**(예: `LanguageCode`)을 받는다.
  타입이 계약을 강제하므로 서비스 안에 방어 코드를 두지 않는다.
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

- 스택: Kotlin 2.3 / JDK 21 toolchain / Spring Boot 4.1, Gradle 멀티모듈(Kotlin DSL) — 모듈러 모놀리스
  (ADR-0008·0016 — 모듈 7개: `:common`·`:api`·`:batch`·`:infra:{llm,auth,redis,storage}`).
  영속: MySQL(+통합 테스트는 MySQL Testcontainers) + Redis(refresh token), 마이그레이션 Flyway. LLM: Spring AI 2.0.
- 실행 bootJar 는 둘: `:api`(web, 진입점 `com.kbap.KbapApiApplication` — 패키지 루트라 전 계층 스캔)와
  `:batch`(배치, 진입점 `com.kbap.batch.KbapBatchApplication`). 공통 빌드 설정은
  `buildSrc` 컨벤션 플러그인(`kbap.*`)에 둔다.
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

**Version**: 6.0.0 | **Ratified**: 2026-06-25 | **Last Amended**: 2026-07-28
