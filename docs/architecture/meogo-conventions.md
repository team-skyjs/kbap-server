# Conventions (규범)

DDD 적용 방식, 모듈 구성, 도메인 간 의존 규칙을 규정하는 **상세 규범 문서**. 모든 신규 코드와 설계는 이 문서를 따른다.

> **구속 원칙의 단일 출처는 헌법** [`.specify/memory/constitution.md`](../../.specify/memory/constitution.md)다(Spec Kit `/speckit-plan`의 Constitution Check가 강제). 이 문서는 그 원칙을 따르는 **상세 "어떻게"**(패키지 레이아웃·빌딩블록·세부 규칙)를 담는 레퍼런스다. 원칙과 상세가 충돌하면 **헌법이 우선**한다.
>
> **이 문서는 "무엇을 지켜야 하는가"(규칙)만 적는다.** 설계 배경·근거는 [`kbap-api-module-structure.md`](./kbap-api-module-structure.md), BC 정의는 [`domains/README.md`](./domains/README.md)에 있다. 코드에 직접 영향을 주는 핵심 규칙은 루트 [`CLAUDE.md`](../../../CLAUDE.md)에 요약+링크로 노출한다.

---

## DDD 정의

- **Bounded Context** — `kbap-api` 컨테이너 직속의 **Gradle subproject + 패키지 경계**로 둔다(평탄화 — `:domain:food` 등). Active BC는 `food`, `member`, `scan`, `avoidance`, `research` 5개 ([`domains/README.md`](./domains/README.md)). `research`는 미스 메뉴 조사·종합 파이프라인(배치 전용, [ADR-0004](../adr/0004-research-bounded-context.md)). `review` subproject는 제품 기획 흔적을 보존한 placeholder이며, 현재 도메인 설계·초기 구현 범위에서는 제외한다.
- **Aggregate** — Aggregate Root를 통해서만 내부 상태를 변경한다. `Food`와 `Ingredient`는 같은 BC라도 같은 Aggregate가 아니다(관계는 `FoodIngredient`가 `ingredientId`로 참조). **Aggregate Root는 `@com.kbap.core.stereotype.AggregateRoot`로 표시한다** — 도메인 객체 마커일 뿐 Spring 빈이 아니므로 `@DomainService`와 달리 `@Component`를 붙이지 않는다(컴포넌트 스캔 대상 아님). 현재 표시 대상: `MenuScan`(scan), `Food`·`Ingredient`(food). 경계는 추후 ArchUnit으로 강제한다.
- **Entity / Value Object** — 다른 Aggregate·Context의 객체 전체를 직접 들지 않는다. **ID·코드·스냅샷 값**으로 참조한다.
- **스냅샷** — 시간이 지나면 원본이 바뀌는 값(스캔 당시 위험도·매핑 음식명·종합 재료 정보)은 스냅샷으로 보존한다. 최신 판정은 필요 시 재계산한다. 과거 결과를 현재 데이터 변경에 맞춰 덮어쓰지 않는다.
- **Domain Event vs Integration Event** — **in-process 도메인 이벤트**(api 내부, 컨텍스트 간)의 의미/이름/payload 계약은 `:core` 또는 도메인 모듈(도메인 언어)에 둔다. **브로커를 타고 다른 앱(예: 알림 컨슈머)이 받는 통합 이벤트**는 `common`에 두고, 도메인 타입을 참조하지 않는 평면 값(ID·코드·스냅샷)만 담는다. 브로커(Kafka/RabbitMQ/SQS) 연결·직렬화·retry·DLQ 같은 기술 구현은 `:infra:external`에 둔다.
- **도메인 모듈 공개 API** — 도메인 모듈은 **도메인 모델·도메인 서비스·Spring Data 리포지토리**를 공개한다([ADR-0014](../adr/0014-relax-persistence-encapsulation.md) — KB-220 에서 `internal` 캡슐화 폐기). 소비 계층은 도메인 로직이 필요하면 도메인 서비스를, 단순 영속 접근이면 리포지토리를 직접 쓴다 — 리포지토리 위임 외 로직이 없는 창구 서비스는 만들지 않는다. 도메인 로직(검증·상태 전이·정책)은 여전히 도메인 서비스가 소유한다. 리포지토리 port 인터페이스·어댑터는 두지 않는다([ADR-0012](../adr/0012-dissolve-persistence-module-and-ports.md)).

## 모듈 구성 (멀티모듈)

레포는 **멀티앱**이다 — `kbap-api`(web) 컨테이너와 `kbap-batch`(배치) 앱, 둘이 공유하는 `common`. `kbap-api` 컨테이너 안에 실행/조율/공통/외부 연동 + 도메인 컨텍스트 leaf 모듈이 평탄하게 들어간다. (상세: [`kbap-api-module-structure.md`](./kbap-api-module-structure.md))

| 모듈 | 책임 |
|------|------|
| `:app:api` | web bootJar, Controller, API DTO, 인증/인가, 예외 응답 변환, Flyway 스키마 owner |
| `:application` | 유스케이스 조율(도메인 서비스 조합), transaction boundary, Input/Result 경계 타입, 외부 client seam 호출 |
| `:domain:{food,member,scan,avoidance}` | Active 도메인 컨텍스트 — 도메인 모델(=JPA 엔티티) + 도메인 서비스(비즈니스 로직 소유) + Spring Data 리포지토리(공개 — [ADR-0014](../adr/0014-relax-persistence-encapsulation.md)) |
| `:domain:research` | Active 도메인. 미스 메뉴 조사·3개 LLM 종합 정책(순수 로직 — 영속 없음). **배치 전용**(web 미노출) ([ADR-0004](../adr/0004-research-bounded-context.md)) |
| `:domain:review` | Deferred placeholder. 현재 구현 범위 제외, 추후 리뷰 기능 재개 시 별도 컨텍스트로 다시 설계 |
| `:core` | 공통 타입·예외·유틸·외부 client seam·공유 vocabulary(`LanguageCode`·id 값 클래스 `FoodId`/`MemberId`) + 영속 공통(`BaseEntity`·`EntityStatus`, jakarta/hibernate `compileOnly`). 애플리케이션 코드는 Spring-free |
| `:infra:llm` | LLM 외부 연동 어댑터(Spring AI 3모델 fan-out) — 배치가 직접 의존 ([ADR-0010](../adr/0010-llm-adapter-module-named-infra-llm.md)) |
| `:app:batch` | 배치 bootJar. 도메인 서비스를 직접 조합해 잡 실행. flyway off. **미스 메뉴 재료 조사 + 9개국어 번역 LLM 파이프라인을 하루 1회 실행**([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)) |
| `:common` | 앱 간 공유 — 통합 이벤트·DTO·기술 공통(logback·유틸·어노테이션). web/jpa/도메인 의존 금지, Spring-free |

- **패키지 레이어링** — 각 도메인 subproject 는 `com.kbap.domain.<context>` 를 루트 패키지로 삼고, 도메인 모델·도메인 서비스·영속 코드가 한 패키지에 함께 산다.
- **얇은 Controller** — `:app:api`은 HTTP 변환·인증/인가에 집중한다. Application Service는 `:app:api`이 아니라 `:application`에 둔다.
- **인증/인가** — 별도 BC로 분리하지 않고 `member` 내부 하위 영역으로 두되, 프로필/식이 제한 관리와 **패키지·책임을 분리**한다. 토큰 발급·세션·보안 필터는 도메인이 아니라 API/security 계층 책임.

## 도메인 모듈 빌딩블록 & 패키지 레이아웃

한 컨텍스트 모듈(`:domain:<context>`)에 들어가는 클래스 종류와 배치([ADR-0012](../adr/0012-dissolve-persistence-module-and-ports.md)·[ADR-0014](../adr/0014-relax-persistence-encapsulation.md)). **전부 public 이다 — 엔티티가 곧 도메인 모델이고, 소비 계층은 리포지토리를 직접 참조할 수 있다(KB-220).**

| 종류 | 설명 | 예시 |
|------|------|------|
| JPA Entity (= 도메인 모델) | 식별자·생명주기를 가진 도메인 객체이자 영속 표현. 도메인 메서드를 내장하고 별도 도메인 모델·`toDomain`/`from` 변환을 두지 않는다. **연관관계 애너테이션 금지 — 참조는 id 값 컬럼** | `Food`, `Member` |
| Value Object | 식별자 없는 값 | `MemberProfile`, `Ranking` |
| 도메인 서비스 | **비즈니스 로직 소유**(`@Service`) — 검증·상태 전이·정책·유비쿼터스 언어 행위. 리포지토리 위임만 하는 창구 메서드를 두지 않는다 | `FoodService`, `MemberService` |
| Spring Data Repository | 기술 저장소(공개) — 도메인 로직이 필요 없는 소비 계층이 직접 참조 | `FoodJpaRepository` |

**패키지 레이아웃 예시 (`domain/food`)**

```
com.kbap.domain.food             # 루트 — 서비스·리포지토리·seam
├── FoodService.kt               # 도메인 서비스 (@Service, 비즈니스 로직)
├── FoodJpaRepository.kt         # Spring Data (공개)
└── model/                       # 도메인 모델 — 엔티티·값 객체·enum
    ├── Food.kt                  # JPA 엔티티 = 도메인 모델(도메인 메서드 내장)
    └── FoodContentStatus.kt
```

## 도메인 객체 불변성 & 영속 변환 ⭐

- **도메인 ↔ JPA 변환은 JPA 엔티티 안에 둔다.** 별도 Mapper 클래스나 adapter 확장함수로 흩지 않는다. 엔티티에 도메인 복원 `toDomain()` 인스턴스 메서드와 `companion object { fun from(domain): Entity }` 팩토리를 두고, **도메인 서비스**는 `Entity.from(domain)`·`entity.toDomain()`만 호출한다. (도메인 모델 클래스는 JPA를 모른다 — ArchUnit 이 강제.)
- **도메인 객체는 불변(immutable)으로 둔다.** 모든 상태는 `val`. 상태를 바꾸는 도메인 메서드는 객체를 변형하지 않고 **새 인스턴스를 반환**한다. 데이터 클래스의 public `copy` 노출 대신 **`private fun copy(...)`** 를 직접 두어 통제된 복제만 허용하고, 상태 변경 메서드가 이를 호출한다.

```kotlin
fun increaseStock(quantity: Int): Product {
    val newStock = stock + quantity
    return copy(
        stock = newStock,
        status = if (status == ProductStatus.SOLD_OUT && newStock > 0) ProductStatus.ON_SALE else status,
    )
}

private fun copy(stock: Int = this.stock, status: ProductStatus = this.status) =
    Product(id, sellerId, name, price, stock, category, thumbnailUrl, status)
```

## 도메인 간 의존 규칙

1. **의존 방향** — `:app:api` → `:application` → `:domain:*` → `:core`. `:core`는 모두가 의존 가능. 외부 시스템 클라이언트(LLM·소셜 인증 등)는 **seam 인터페이스로만** 사용한다(폐기된 것은 리포지토리 port 이지 외부 어댑터 seam 이 아니다). `:app:batch`는 필요한 도메인 모듈·`:infra:llm`을 직접 의존하고, `:common`은 앱들이 공유하되 web/jpa/도메인에 의존하지 않는다.
2. **도메인 간 직접 의존 금지** — BC는 서로의 내부 구현을 직접 알지 않는다. **조합은 `:application`의 Application Service에서** 한다. (예: 메뉴판 판정은 `scan`·`food`·`member`·`avoidance`를 쓰고, 미스 메뉴 조사는 `research`·`food`를 쓰지만 서로 직접 의존하지 않음)
3. **영속 소유** — JPA Entity / Spring Data Repository 는 각 도메인 모듈이 소유하되 **public** 이다([ADR-0014](../adr/0014-relax-persistence-encapsulation.md) — KB-220). 소비 계층(`:application`·`:app:*`)은 단순 영속 접근이면 리포지토리를 직접 쓰고, 이때 트랜잭션 경계도 스스로 선언한다(예: 배치 진행 저장의 `TransactionTemplate(REQUIRES_NEW)`). 위임 전용 창구 서비스는 만들지 않는다.
3-1. **JPA 연관관계 금지** ⭐ — 엔티티 간 `@OneToMany`·`@ManyToOne`·`@OneToOne`·`@ManyToMany` 를 두지 않는다. 참조는 **id 값 컬럼**(공유 값 클래스 `FoodId`·`MemberId`)으로만 들고, 연관 데이터는 도메인 서비스가 id(목록)로 명시 조회한다. 지연 로딩이 없어 N+1·`LazyInitializationException` 이 구조적으로 불가하다. 외래키 제약은 Flyway 스키마가 강제한다(ON DELETE 없음 — 소프트 삭제 구조). (ArchUnit 전면 금지 규칙)
4. **avoidance 입력 VO 규칙** ⭐ — `avoidance`는 `food`/`member`의 **엔티티·영속 모델에 직접 의존하지 않는다.** `avoidance`는 자기 전용 입력 VO(`AvoidanceInput`: 사용자 식이 제한 조건 + 음식 재료 목록 + 포함 스코어 + 알러지/종교/비건 매핑 + 원문 메뉴명)를 정의하고, **`:application`이 `food`·`member` 데이터를 그 VO로 변환해 전달**한다. 판정 결과(`AvoidanceResult`)도 도메인 결과 객체로 반환한다.
5. **공통 코드 체계** — 알러지/종교/비건 제한 코드는 `member`(사용자 조건)와 `food`(재료 매핑) 양쪽에서 비교 가능한 **공통 코드**로 둔다.
6. **외부 호출과 트랜잭션** — LLM 등 외부 API 호출을 DB 트랜잭션 안에서 길게 잡지 않는다. **스캔 응답 경로(`kbap-api`)는 LLM을 호출하지 않는다** — 캐시 히트 메뉴만 판정하고, 캐시 미스는 결과 없음으로 응답하며 미스 메뉴명을 `research`에 적재한다. LLM 병렬 호출·종합·9개국어 번역은 `research`(배치)가 하루 1회 수행한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)).
7. **API 노출** — 도메인/영속 모델을 API 응답으로 그대로 노출하지 않는다. 음식 원본 정보와 사용자별 위험도 판정 결과는 내부적으로 분리해 다룬다.
8. **배치 전용 조합 로직** ⭐ — `research` 처리처럼 **사용자 API가 호출하지 않고 배치만 트리거하는 조합 유스케이스**는 `kbap-batch`(Job 껍데기)가 아니라 `:application`에 둔다(조합은 application에서만 — 규칙 2). 단 web 진입점이 호출 못 하게 **전용 패키지로 격리하고 ArchUnit으로 강제**한다. `kbap-batch`는 그 유스케이스를 스케줄에 맞춰 호출만 한다. (분리 트리거·근거는 [ADR-0004](../adr/0004-research-bounded-context.md).)

## 언어 / 데이터 정책

- 음식 콘텐츠(음식명·설명·재료명·알러지/종교·비건 주의 성분)는 **한국어(`ko`) 원문 + 9개 대상 언어**로 사전 번역해 DB에 저장한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)). 9개 언어: `zh-Hans`(중국어 간체) · `en`(영어) · `ja`(일본어) · `zh-Hant`(중국어 번체) · `vi`(베트남어) · `id`(인도네시아어) · `th`(태국어) · `ru`(러시아어) · `es`(스페인어). 번역은 `research`(배치)가 LLM으로 생성하고 `food`가 저장한다.
- **표시 언어(`lang`) 규약 (KB-201 · [ADR-0013](../adr/0013-lang-english-fallback.md))** — 표시 언어를 받는 API 는 다음을 따른다.
  - `lang` 은 **필수** 쿼리 파라미터다. 기본값(`defaultValue`)을 두지 않는다. 누락·빈 값·공백은 **400 `COMMON-002`**.
  - 값이 지원 10종과 **정확히 일치**하면 그 언어, 그 외(미지원 코드·대소문자 불일치·지역 변형·앞뒤 공백)는 **`en` 폴백**. 언어 코드 값을 사유로 하는 400 은 없다.
  - 회원 프로필은 앱 언어를 보관하지 않는다 — 응답 언어는 전 API 가 요청 `lang` 으로만 정한다(KB-229 · [ADR-0015](../adr/0015-scan-lang-unification-and-profile-language-removal.md) — 마지막 예외였던 스캔 API 를 전환하고 프로필 `appLanguage` 를 제거했다).
  - 근거: `lang` 은 사용자가 고르는 값이 아니라 기기 설정에서 흘러드는 값이라, 미지원 기기 언어에 400 을 주면 화면이 열리지 않는다. 상세·트레이드오프는 ADR-0013.
- **외부 입력 검증은 요청 경계(컨트롤러)가 소유한다 (KB-201)** — 필수 여부·빈 값 판정은 web 계층의 **요청 DTO**(`@field:NotBlank` 등 + `@Valid @ModelAttribute`)가 처리하고, 도메인·애플리케이션 서비스는 **확정된 값**(예: `LanguageCode`)을 받는다. 타입이 계약을 강제하므로 서비스 안에 방어 코드를 두지 않는다.
  - 쿼리 파라미터에도 요청 DTO 를 쓴다 — 종래 `*Request` DTO 는 POST/PUT 본문 전용이었으나 KB-201 이 쿼리 파라미터로 확장한 첫 사례다. 신규 API 와 기존 API 이행은 이 패턴을 따른다.
  - swagger 는 인터페이스 파라미터에 `@ParameterObject`(springdoc)를 붙여 DTO 를 쿼리 파라미터로 펼치고, 필드 설명은 DTO 의 `@field:Schema` 에 둔다. 펼침이 실패하면 문서가 실제 계약과 어긋나므로 Swagger UI 에서 육안 확인한다.
- 정적 UI 문구는 사전 번역해 `:core` 또는 별도 supporting resource로 제공한다. **음식 데이터 번역 정책과 분리**한다. (BC로 올리지 않음)
- LLM 원본 응답을 도메인 판단에 직접 쓰지 않는다. `:application`에서 종합한 결과만 `Food`/`FoodIngredient`에 반영한다.

## Flyway 마이그레이션 버전 규칙

스키마 owner 는 `:app:api`(`src/main/resources/db/migration`)이고 DB 를 공유하는 구조라, 여러 개발자가 병렬 브랜치에서 각자 "다음 정수"(V10, V11 …)를 잡으면 머지 시 같은 버전 번호가 충돌한다. 이를 없애기 위해 **신규 마이그레이션은 생성 시각 기반 점 구분 timestamp 버전**을 쓴다. (근거: KB-44, Flyway 공식 문서 `concepts/migrations`.)

### 파일명 포맷

```
V<version>__<description>.sql
```

- **신규**: `<version>` = `yyyy.MM.dd.HH.mm.ss` (점 구분, 초 단위, 각 파트 두 자리 zero-pad). 값은 **파일 생성 시점의 로컬 현재 시각**.
  - 예: `V2026.07.05.14.30.12__add_review_table.sql`
- Flyway 공식 문서가 유효 예시로 제시한 포맷이다(`2013.01.15.11.35.56`). Flyway 는 버전을 **숫자 파트열로 수치 정렬**한다(*"versions are sorted numerically"*).
- `<description>` 은 소문자 스네이크 슬러그(변경 목적).

### 기존 파일 일회성 전환 & 프로덕션 후 freeze

- 기존 정수 마이그레이션(`V1`~`V10`)은 **로컬 DB 전용·프로덕션 이전 단계에서 각 파일의 최초 커밋 시각 기준 timestamp 로 일괄 전환**했다(KB-44). 같은 커밋에 묶여 시각이 동일한 파일은 원래 정수 순서를 보존하도록 초를 1초씩 밀어 정렬을 유지했다(예: `V1`,`V2`,`V3`,`V4` → `...20.35.02`,`03`,`04`,`05`).
- 전환이 안전했던 이유: 공유/프로덕션 DB 가 없어 로컬 `flyway_schema_history` 를 재생성(DB DROP+CREATE 후 재적용)하면 됐기 때문이다. 파일 내용은 바꾸지 않고 파일명(버전)만 바꿨다.
- **한 번이라도 공유/프로덕션 DB 에 적용된 마이그레이션은 그 시점부터 동결**한다 — 이후 수정·리네임은 checksum/history 파손으로 배포 장애를 부른다. 따라서 이런 일괄 전환은 프로덕션 배포 전 **단 한 번만** 가능하다.

### out-of-order 허용 + 순서-독립 원칙

- 버전이 **생성 시각** 기준이라, 먼저 만들고 늦게 머지한 마이그레이션은 이미 적용된 최신보다 **과거 버전**이 된다(= out-of-order). Flyway 기본값(`out-of-order=false`)에서는 이때 validate 가 실패해 부팅이 거부된다.
- 이를 허용하기 위해 **`spring.flyway.out-of-order: true`** 를 베이스 `app/api/src/main/resources/application.yml` 에 둔다(전 프로필 상속). 과거 버전도 도착 순서대로 적용되며, `flyway_schema_history.installed_rank` 가 실제 실행 순서를 보존한다.
- 공식 문서는 out-of-order 를 *"rerunning the entire migration history might produce different results"* 로 경고한다. 이 위험은 **각 마이그레이션이 다른 미적용 마이그레이션의 실행 순서에 의존하지 않을 때만** 무해하다. 따라서 **마이그레이션은 순서-독립적으로 작성**한다(같은 배포 묶음의 두 스크립트가 순서 의존적으로 같은 테이블/컬럼을 건드리지 않는다).

### 금지 사례

1. 신규 마이그레이션에 정수 버전(`V11`) 사용 — 병렬 충돌 재발.
2. **공유/프로덕션 DB 에 이미 적용된** 마이그레이션 파일 수정·리네임 — checksum/history 파손(로컬 전용·프로덕션 이전 단계의 일괄 전환은 예외).
3. 순서 의존 마이그레이션 작성 — out-of-order 시 결과가 달라질 수 있음.

### 검증

테스트 스위트는 H2 + flyway off 라 마이그레이션을 실행하지 않는다. Flyway 실동작(정렬·out-of-order·checksum)은 **로컬 docker MySQL** 부팅으로 실측 검증한다(절차: `specs/kb-44-flyway-timestamp-versioning/quickstart.md`).
