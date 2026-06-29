# Phase 0 Research: 회피·주의 성분 카탈로그

본 기능의 기술 결정과 근거. 모든 NEEDS CLARIFICATION 해소.

## D-STORE: 카탈로그 저장 방식 — 컴파일 enum (DB 아님), 소유 컨텍스트 avoidance

- **Decision**: `:core:avoidance` 의 컴파일 enum 으로 저장한다. `AvoidanceCategory`(3종) + `AvoidanceSubstance`(81종). DB 테이블·Flyway 시드·Mongo 없음.
- **Rationale**:
  - 읽기 전용·런타임 불변(spec FR-008)인 고정 81종 taxonomy 라 DB 의 동적성이 불필요.
  - `app:batch` 의 LLM 잡이 프롬프트에 분류·코드를 포함해야 하고 평가(application)도 같은 값을 참조한다(ADR-0008). **`:core:avoidance` enum = 단일 출처**, 컴파일 타임 정합으로 드리프트 0(안전 직결).
  - enum 은 코드 유일성(FR-005)·분류 도메인 고정(FR-006)을 **컴파일러가 강제**.
- **Alternatives considered**:
  - *DB 테이블 + Flyway 시드*: CRUD 가 없어 과설계. 소비자가 런타임 정합만 얻고, 마이그레이션·정합 테스트·로딩 부담 추가. 기각.
  - *`:core:kernel` 에 배치*: kernel 은 "모두가 의존하는" 공유 커널. 회피 성분은 **avoidance 컨텍스트가 의미를 부여하는 도메인 vocabulary** 라 소유 컨텍스트(avoidance)가 정확. kernel 배치는 소유 불명확한 dumping. food·member 가 enum 을 직접 참조하지 않으므로(아래 D-OWNER) kernel 공유가 불필요. 기각.
  - *`:common` 에 배치*: `:common` 은 도메인 무관 기술 공통(통합 이벤트·유틸). 도메인 vocabulary 부적합. 기각.

## D-OWNER: 소유 = 물리 위치 = `:core:avoidance`

- **Decision**: 카탈로그는 **`:core:avoidance` 가 소유하고 그 모듈에 둔다**(의미상 소유=물리 위치 일치, spec clarify). 본 기능은 카탈로그(데이터)만 만들고 평가 Spec·판정 로직은 후속.
- **Rationale (원칙 II 로 성립)**:
  - 회피·주의 성분은 **회피 판정이 의미를 부여하는 ubiquitous language**. 그 컨텍스트가 소유하는 게 DDD 정석.
  - 다른 컨텍스트는 이 enum 을 **import 하지 않고 코드(문자열)로 참조**한다 — 원칙 II "다른 Context 객체를 직접 들지 않고 ID·코드·스냅샷 값으로 참조". 따라서 **도메인 간 직접 의존 0**:
    - **member**: 사용자 회피 선호를 성분 *코드 문자열* 로 저장(enum 미import).
    - **food**: 재료 자체 vocabulary 만 보유(깨끗) — 재료↔성분 매핑은 application 조합에서.
    - **application**: food·member·avoidance 를 모아 코드→enum 변환·평가(조합은 application 에서만 — 원칙 II).
    - **app:batch**: `:core:avoidance` 직접 의존(ADR-0008, 앱→도메인 의존이지 도메인 간 의존 아님)해 프롬프트에 코드·분류 주입.
  - kernel 에 두면(이전 안) 소유가 흐려지고, 실제로 food·member 가 enum 을 직접 참조하지 않으므로 kernel 공유의 이점이 없다.
- **Note**: 향후 평가 유스케이스 — avoidance 가 `Spec`(입력 형상)·판정 도메인 서비스·결과 타입을 정의, application 이 food·member 를 모아 Spec 으로 변환·호출. 본 기능 범위 밖.

## D-LANG: `LanguageCode` 를 `:core:food` → `:core:kernel` 로 이동

- **Decision**: `com.meogo.core.food.LanguageCode` 를 `com.meogo.core.kernel.lang.LanguageCode` 로 **이동**한다. 동작·enum 값 불변, import 경로만 갱신.
- **Rationale**:
  - `LanguageCode` 를 이제 **food(음식 번역)와 avoidance(성분명 번역) 두 도메인이 함께** 쓴다. 한 도메인에 두면 다른 도메인이 그 모듈을 의존하게 돼 원칙 II/ArchUnit(도메인 간·`kernel→domain` 의존 금지, 005 `ModuleBoundaryTest`) 위반. **두 도메인이 공유하는 vocabulary 의 자리는 kernel** 뿐.
  - `LanguageCode`(ko + 9개 대상 언어)는 ADR-0003/헌법 원칙 V 의 **서비스 전역 vocabulary**. food 가 처음 도입했을 뿐 본질은 공유 개념.
  - 이동 후 food·persistence·application·avoidance 가 kernel 의 같은 타입을 그대로 쓴다(중복 제거).
- **Impact (동작 불변, import 경로만)**: `core/food/FoodRepository.kt`, `infra/persistence/food/FoodRepositoryAdapter.kt`, `application/client/food/usecase/LanguageResolver.kt`, `application/client/food/usecase/GetFoodDetailUseCase.kt`, 그리고 `LanguageCodeTest` 이동. `:core:food` 의 `LanguageCode.kt` 삭제.
- **Alternatives considered**:
  - *kernel 에서 문자열 lang 코드 사용*: 타입 안전 상실·언어 개념 중복. 기각.
  - *food 에 그대로 두고 kernel 이 의존*: 의존 방향·ArchUnit 위반. 기각.

## D-TRANS: 번역 명칭(81 × 9) 저장 위치

- **Decision**: **컴파일 데이터로 `:core:avoidance` 에 둔다.** enum 은 `categories` + `koName`(ko 원문)만 보유. 9개 대상 언어 번역은 별도 객체 `AvoidanceSubstanceTranslations` 가 `Map<AvoidanceSubstance, Map<LanguageCode, String>>` 로 보유(현재 mock). resolver `AvoidanceCatalog.displayName(substance, lang)` 가 요청 언어 → 없으면 **ko 폴백**(FR-004).
- **Rationale**:
  - `:core:avoidance` 는 완전 Spring-free(domain-conventions) → Spring `MessageSource` 불가. 리소스 번들(properties) I/O 보다 **데이터-as-코드 Map** 이 타입 안전(키가 enum)·무 I/O·테스트로 완전성 검증 가능.
  - enum 선언을 `categories`+`koName` 만으로 가볍게 유지하고, 부피가 큰(그리고 mock→실제로 통째 교체될) 번역은 분리.
  - 완전성(모든 성분 × 9 언어)·폴백을 단위 테스트로 강제(안전 직결).
- **Alternatives considered**:
  - *enum 생성자에 번역 Map 인라인*: 응집은 높으나 81개 선언이 비대. 분리 객체로 가독성 확보. (동등하게 유효 — tasks 에서 뒤집어도 무방.)
  - *properties 리소스 번들*: i18n 관용이나 kernel 의 Spring-free·타입 안전·완전성 테스트 이점이 약함. 기각.
- **Mock 정책**: 콘텐츠 미확정(spec Assumptions) → 대표 항목 mock 명칭/번역. 확정 값 수령 시 `AvoidanceSubstance` 선언과 `AvoidanceSubstanceTranslations` Map 을 교체. 구조·테스트는 본 단계에서 고정.

## D-PRINV: 헌법 원칙 V("DB 에 저장") 충돌 처리

- **Decision**: 정당화된 의도적 일탈로 진행하고(plan §Complexity), 원칙 V 범위 명확화는 별도 개정으로 후속.
- **Rationale**: 원칙 V 의 *목적* 은 "외국인에게 모국어 안전 정보 제공 + 콘텐츠/UI 번역 책임 분리"다. enum 저장도 **ko 원문 + 9개 대상 언어 사전 번역 + ko 폴백** 을 그대로 만족 → 목적 충족. 충돌은 저장 *매체*("DB") 문구뿐. 고정 reference taxonomy(LLM 생성·검수 상태 무관)는 동적 메뉴 콘텐츠와 성격이 달라 컴파일 저장이 합리적.
- **Follow-up**: `/speckit-constitution` 으로 ① 원칙 V 에 "고정 reference taxonomy 는 컴파일 enum 저장 허용" 단서 추가, ② ADR-0008 모듈명 동기화(`:meogo-api:*` → 현행), ③ `assessment` → `avoidance` BC 리네임을 헌법 원칙 II 컨텍스트 열거·ADR·architecture 문서에 반영(코드/빌드/004 문서는 이미 완료). 본 plan 과 분리(governance).

## D-TEST: 테스트 전략

- `:core:avoidance:test`(+ `LanguageCode` 이동 회귀는 `:core:kernel:test`), Kotest `BehaviorSpec`(한국어 given/when/then). Spring 불필요(순수 enum) → 빠른 단위 테스트.
- 강제할 불변식: 81종 존재 / 각 성분 분류 1~3개·중복 없는 Set / 코드 유일(enum 보장 → 개수·name 검증) / ko 명칭 비공백 / 분류값 3종 도메인 / resolver 가 등록 언어는 해당 번역, 미등록은 ko 폴백 / (완전성) 모든 성분이 9개 대상 언어 키 보유 또는 폴백 동작.
