# Conventions (규범)

DDD 적용 방식, 모듈 구성, 도메인 간 의존 규칙을 규정하는 **상세 규범 문서**. 모든 신규 코드와 설계는 이 문서를 따른다.

> **구속 원칙의 단일 출처는 헌법** [`.specify/memory/constitution.md`](../../.specify/memory/constitution.md)다(Spec Kit `/speckit-plan`의 Constitution Check가 강제). 이 문서는 그 원칙을 따르는 **상세 "어떻게"**(패키지 레이아웃·빌딩블록·세부 규칙)를 담는 레퍼런스다. 원칙과 상세가 충돌하면 **헌법이 우선**한다.
>
> **이 문서는 "무엇을 지켜야 하는가"(규칙)만 적는다.** 설계 배경·근거는 [`meogo-api-module-structure.md`](./meogo-api-module-structure.md), BC 정의는 [`domains/README.md`](./domains/README.md)에 있다. 코드에 직접 영향을 주는 핵심 규칙은 루트 [`CLAUDE.md`](../../../CLAUDE.md)에 요약+링크로 노출한다.

---

## DDD 정의

- **Bounded Context** — `meogo-api` 컨테이너 직속의 **Gradle subproject + 패키지 경계**로 둔다(평탄화 — `:meogo-api:food` 등). Active BC는 `food`, `member`, `scan`, `assessment`, `research` 5개 ([`domains/README.md`](./domains/README.md)). `research`는 미스 메뉴 조사·종합 파이프라인(배치 전용, [ADR-0004](../adr/0004-research-bounded-context.md)). `review` subproject는 제품 기획 흔적을 보존한 placeholder이며, 현재 도메인 설계·초기 구현 범위에서는 제외한다.
- **Aggregate** — Aggregate Root를 통해서만 내부 상태를 변경한다. `Food`와 `Ingredient`는 같은 BC라도 같은 Aggregate가 아니다(관계는 `FoodIngredient`가 `ingredientId`로 참조).
- **Entity / Value Object** — 다른 Aggregate·Context의 객체 전체를 직접 들지 않는다. **ID·코드·스냅샷 값**으로 참조한다.
- **스냅샷** — 시간이 지나면 원본이 바뀌는 값(스캔 당시 위험도·매핑 음식명·종합 재료 정보)은 스냅샷으로 보존한다. 최신 판정은 필요 시 재계산한다. 과거 결과를 현재 데이터 변경에 맞춰 덮어쓰지 않는다.
- **Domain Event vs Integration Event** — **in-process 도메인 이벤트**(api 내부, 컨텍스트 간)의 의미/이름/payload 계약은 `:meogo-api:core` 또는 도메인 모듈(도메인 언어)에 둔다. **브로커를 타고 다른 앱(예: 알림 컨슈머)이 받는 통합 이벤트**는 `meogo-common`에 두고, 도메인 타입을 참조하지 않는 평면 값(ID·코드·스냅샷)만 담는다. 브로커(Kafka/RabbitMQ/SQS) 연결·직렬화·retry·DLQ 같은 기술 구현은 `:meogo-api:infra`에 둔다.
- **Repository** — 도메인 모듈은 외부에 **Domain Entity와 DomainRepository interface만** 공개한다. 구현체는 도메인 모듈 내부에 숨긴다.

## 모듈 구성 (멀티모듈)

레포는 **멀티앱**이다 — `meogo-api`(web) 컨테이너와 `meogo-batch`(배치) 앱, 둘이 공유하는 `meogo-common`. `meogo-api` 컨테이너 안에 실행/조율/공통/외부 연동 + 도메인 컨텍스트 leaf 모듈이 평탄하게 들어간다. (상세: [`meogo-api-module-structure.md`](./meogo-api-module-structure.md))

| 모듈 | 책임 |
|------|------|
| `:meogo-api:api` | web bootJar, Controller, API DTO, 인증/인가, transaction boundary, 예외 응답 변환, infra 조립(runtimeOnly), Flyway 스키마 owner |
| `:meogo-api:application` | 유스케이스 조율, 도메인 컨텍스트 조합, Command 입력, 외부 client port 호출 |
| `:meogo-api:{food,member,scan,assessment}` | Active 도메인 규칙. 각 컨텍스트는 `:meogo-api:core`만 직접 의존하고 영속 adapter를 자기 모듈 안에 캡슐화 |
| `:meogo-api:research` | Active 도메인. 미스 메뉴 조사 대기열 + 3개 LLM 종합 정책(순수 도메인 서비스). **배치 전용**(web 미노출), 종합 결과를 `food`가 영속 ([ADR-0004](../adr/0004-research-bounded-context.md)) |
| `:meogo-api:review` | Deferred placeholder. 현재 구현 범위 제외, 추후 리뷰 기능 재개 시 별도 컨텍스트로 다시 설계 |
| `:meogo-api:core` | 공통 타입·예외·이벤트 계약·유틸 (Spring-free) |
| `:meogo-api:infra` | 메시지큐, 외부 API(LLM·storage·번역·알림), 이벤트 발행/구독 client |
| `:meogo-batch` | 배치 bootJar. `:meogo-api:application` 유스케이스를 트리거(단일 모듈, 추후 분리). flyway off. **미스 메뉴 재료 조사 + 9개국어 번역 LLM 파이프라인을 하루 1회 실행**([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)) |
| `:meogo-common` | 앱 간 공유 — 통합 이벤트·DTO·기술 공통(logback·유틸·어노테이션). web/jpa/도메인 의존 금지, Spring-free |

- **패키지 레이어링** — 각 도메인 subproject 내부는 BC별 패키지(`com.meogo.domain.<context>`)를 루트로 삼고, 그 아래에 도메인 모델과 `adapter`/`infrastructure`(영속 구현) 패키지를 둔다.
- **얇은 Controller** — `meogo-api`는 HTTP 변환·인증/인가에 집중한다. Application Service는 `meogo-api`가 아니라 `meogo-application`에 둔다.
- **인증/인가** — 별도 BC로 분리하지 않고 `member` 내부 하위 영역으로 두되, 프로필/식이 제한 관리와 **패키지·책임을 분리**한다. 토큰 발급·세션·보안 필터는 도메인이 아니라 API/security 계층 책임.

## 도메인 모듈 빌딩블록 & 패키지 레이아웃

한 컨텍스트 모듈(`:meogo-api:<context>`)에 들어가는 클래스 종류와 배치. **외부에는 도메인 모델·Repository 인터페이스만 공개하고, 영속 구현은 모듈 내부에 숨긴다.**

**공개 — 다른 모듈이 보는 도메인 언어**

| 종류 | 설명 | 예시 |
|------|------|------|
| Aggregate Root / Entity | 식별자·생명주기를 가진 도메인 객체. 내부 상태는 Root를 통해서만 변경 | `Food`, `Ingredient` |
| Value Object | 식별자 없는 불변 값 | `FoodId`, `FoodName`, `AllergenMapping`, (assessment) `AssessmentInput`·`AssessmentResult` |
| 하위 Entity | Aggregate 구성요소 | `FoodIngredient` |
| Domain Repository 인터페이스(port) | 도메인 언어 저장소 계약, **도메인 엔티티 반환** | `FoodRepository` |
| Domain Service | 한 엔티티에 안 붙는 도메인 규칙 | `AssessmentPolicy` |
| Domain Event | 컨텍스트 내부 이벤트는 모듈 `event` 패키지에, 교차-컨텍스트 계약은 `meogo-core`에 | `FoodCreatedEvent` |
| Domain Exception | 컨텍스트 고유 예외 | `FoodNotFoundException` |

**은닉 — 모듈 내부 `infrastructure`/`adapter` 패키지, 외부 import 금지**

| 종류 | 설명 | 예시 |
|------|------|------|
| JPA Entity / Mongo Document | 영속성 표현(도메인 엔티티와 **별개**) | `FoodJpaEntity` |
| Spring Data Repository | 기술 저장소 | `FoodJpaRepository` |
| Repository 구현체(adapter) | 도메인 Repository 구현 + 매핑 | `FoodRepositoryAdapter` |
| Mapper | 도메인 ↔ 영속 모델 변환 | `FoodEntityMapper` |

**패키지 레이아웃 예시 (`meogo-api/food`)**

```
com.meogo.domain.food
├── Food.kt                 # Aggregate Root
├── FoodId.kt / FoodName.kt # Value Object
├── Ingredient.kt           # 별도 Aggregate Root
├── FoodRepository.kt       # Domain Repository 인터페이스 (공개 port)
├── event/FoodCreatedEvent.kt
└── infrastructure/         # 외부 비공개 (= adapter)
    ├── FoodJpaEntity.kt
    ├── FoodJpaRepository.kt
    ├── FoodRepositoryAdapter.kt   # FoodRepository 구현
    └── FoodEntityMapper.kt
```

> 영속 기술(`data-jpa`/`data-mongodb`)은 `implementation`으로 둬 상위(application/api) 컴파일 클래스패스에 노출되지 않는다(런타임 전이만 허용). 패키지 가시성 + ArchUnit 으로 추가 강제.

## 도메인 간 의존 규칙

1. **의존 방향** — `:meogo-api:api` → `:meogo-api:application` → 도메인 모듈. `:meogo-api:core`는 모두가 의존 가능. `:meogo-api:infra`는 port/adapter로만 연결한다(조립 모듈이 runtimeOnly 주입). `:meogo-batch`는 `:meogo-api:application`을 의존(+infra 조립)하고, `:meogo-common`은 앱들이 공유하되 web/jpa/도메인에 의존하지 않는다.
2. **도메인 간 직접 의존 금지** — BC는 서로의 내부 구현을 직접 알지 않는다. **조합은 `meogo-application`의 Application Service에서** 한다. (예: 메뉴판 판정은 `scan`·`food`·`member`·`assessment`를 쓰고, 미스 메뉴 조사는 `research`·`food`를 쓰지만 서로 직접 의존하지 않음)
3. **영속 모델 비노출** — JPA Entity / Mongo Document / Spring Data Repository / DomainRepository 구현체는 각 도메인 모듈 내부에 숨긴다. `:meogo-api:api`·`:meogo-api:application`은 이들을 import하지 않는다. (패키지 가시성 + 코드 리뷰 + **ArchUnit 테스트**로 강제)
4. **assessment 입력 VO 규칙** ⭐ — `assessment`는 `food`/`member`의 **엔티티·영속 모델에 직접 의존하지 않는다.** `assessment`는 자기 전용 입력 VO(`AssessmentInput`: 사용자 식이 제한 조건 + 음식 재료 목록 + 포함 스코어 + 알러지/종교/비건 매핑 + 원문 메뉴명)를 정의하고, **`meogo-application`이 `food`·`member` 데이터를 그 VO로 변환해 전달**한다. 판정 결과(`AssessmentResult`)도 도메인 결과 객체로 반환한다.
5. **공통 코드 체계** — 알러지/종교/비건 제한 코드는 `member`(사용자 조건)와 `food`(재료 매핑) 양쪽에서 비교 가능한 **공통 코드**로 둔다.
6. **외부 호출과 트랜잭션** — LLM 등 외부 API 호출을 DB 트랜잭션 안에서 길게 잡지 않는다. **스캔 응답 경로(`meogo-api`)는 LLM을 호출하지 않는다** — 캐시 히트 메뉴만 판정하고, 캐시 미스는 결과 없음으로 응답하며 미스 메뉴명을 `research`에 적재한다. LLM 병렬 호출·종합·9개국어 번역은 `research`(배치)가 하루 1회 수행한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)).
7. **API 노출** — 도메인/영속 모델을 API 응답으로 그대로 노출하지 않는다. 음식 원본 정보와 사용자별 위험도 판정 결과는 내부적으로 분리해 다룬다.
8. **배치 전용 조합 로직** ⭐ — `research` 처리처럼 **사용자 API가 호출하지 않고 배치만 트리거하는 조합 유스케이스**는 `meogo-batch`(Job 껍데기)가 아니라 `:meogo-api:application`에 둔다(조합은 application에서만 — 규칙 2). 단 web 진입점이 호출 못 하게 **전용 패키지로 격리하고 ArchUnit으로 강제**한다. `meogo-batch`는 그 유스케이스를 스케줄에 맞춰 호출만 한다. (분리 트리거·근거는 [ADR-0004](../adr/0004-research-bounded-context.md).)

## 언어 / 데이터 정책

- 음식 콘텐츠(음식명·설명·재료명·알러지/종교·비건 주의 성분)는 **한국어(`ko`) 원문 + 9개 대상 언어**로 사전 번역해 DB에 저장한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)). 9개 언어: `zh-Hans`(중국어 간체) · `en`(영어) · `ja`(일본어) · `zh-Hant`(중국어 번체) · `vi`(베트남어) · `id`(인도네시아어) · `th`(태국어) · `ru`(러시아어) · `es`(스페인어). 번역은 `research`(배치)가 LLM으로 생성하고 `food`가 저장한다.
- 정적 UI 문구는 사전 번역해 `meogo-core` 또는 별도 supporting resource로 제공한다. **음식 데이터 번역 정책과 분리**한다. (BC로 올리지 않음)
- LLM 원본 응답을 도메인 판단에 직접 쓰지 않는다. `meogo-application`에서 종합한 결과만 `Food`/`FoodIngredient`에 반영한다.
