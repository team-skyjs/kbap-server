# Implementation Plan: 회피·주의 성분 카탈로그 (3분류 81종, :core:avoidance enum)

**Branch**: `004-avoidance-catalog` | **Date**: 2026-06-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-avoidance-catalog/spec.md`

## Summary

서비스가 다루는 **회피·주의 성분 81종**의 기준 목록(안정 코드 · 1~3개 회피 사유 분류 · ko 원문 명칭 · 9개 대상 언어 번역)을 **읽기 전용 참조 데이터**로 보유한다. 핵심 설계 결정: 이 카탈로그를 **DB 테이블이 아니라 컴파일 enum** 으로, **소유 컨텍스트 `:core:avoidance`** 에 구현한다 — `AvoidanceCategory`(3종) + `AvoidanceSubstance`(81종, 각 1~3개 category 집합 보유) + 컴파일 번역 데이터 + ko 폴백 resolver. 회피·주의 성분은 **회피 판정이 의미를 부여하는 ubiquitous language** 이므로 그 컨텍스트가 소유한다. 다른 컨텍스트는 이 enum 을 import 하지 않고 **코드(문자열)로 참조**한다(원칙 II): member 는 사용자 회피 선호를 코드로 저장, food 재료↔성분 매핑·평가 조합은 **application 계층**에서 수행, `app:batch`(LLM 잡)는 `:core:avoidance` 를 직접 의존(ADR-0008)해 프롬프트에 코드·분류를 주입한다. 읽기 전용·런타임 불변·단일 출처라 enum 이 적합(중복 정의·드리프트(안전 직결) 방지). 영속·Flyway·시드 SQL·번역 테이블·조회 API·평가 로직(Spec·판정)은 본 범위에 없다(후속).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: 순수 Kotlin stdlib. `:core:avoidance`(도메인) 은 `:core:kernel` 만 의존(domain-conventions = `api(:core:kernel)`) — `LanguageCode` 사용. **완전 Spring-free** — Spring/JPA/리소스 I/O 미사용.

**Storage**: **N/A — 영속화하지 않는다.** 카탈로그는 컴파일 상수(enum + 데이터 객체). DB/Flyway/Mongo 무관.

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어), `:core:avoidance:test`(+ kernel 이동 회귀). 불변식·폴백·완전성 단위 테스트.

**Target Platform**: 두 부트앱(`:app:api`·`:app:batch`)이 공유하는 JVM 라이브러리 모듈.

**Project Type**: 모듈러 모놀리스의 공유 커널 추가(라이브러리). web/배치 진입점 변경 없음.

**Performance Goals**: N/A(상수 조회, O(1)~O(81)). 외부 호출·쿼리 없음.

**Constraints**: 읽기 전용(런타임 생성/수정/삭제 경로 없음). 안전 직결 데이터 — 불변식(분류 1~3개·코드 유일·ko 비공백)을 **컴파일/테스트로 강제**.

**Scale/Scope**: 고정 81종 × (1~3 분류) × (ko + 9 대상 언어). 콘텐츠는 현재 mock placeholder, 확정 값 제공 시 교체.

## Constitution Check

*GATE: Phase 0 전 통과 필수, Phase 1 후 재점검.*

> 주의: 헌법 v2.0.0 은 ADR-0008(005) **이전 모듈명**(`:meogo-api:application` 등)을 참조한다. 원칙의 **취지**로 현재 구조(`:core:kernel`·`:app:*` 등)에 매핑해 평가한다. 모듈명 동기화는 별도 헌법 개정(후속, 아래 §Complexity·§후속)으로 처리한다.

| 원칙 | 평가 | 비고 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ 통과 예정 | 모든 enum/resolver/불변식을 실패 테스트 우선(Red→Green)으로 작성. tasks 가 테스트 선행을 강제. |
| **II. Bounded Contexts — No Cross-Domain Coupling** | ✅ 통과(강화) | 카탈로그를 **소유 컨텍스트 `:core:avoidance`** 에 둔다(의미상=물리적 소유 일치). 다른 컨텍스트(food·member)는 enum 을 import 하지 않고 **코드 문자열로 참조**(원칙 II "ID·코드·스냅샷 참조") — 도메인 간 직접 의존 0. 조합은 application 계층에서만. batch 는 `app:batch`→`:core:avoidance` 직접 의존(ADR-0008, 도메인 간 의존 아님). |
| **III. Layered Dependency Direction** | ✅ 통과 | `:core:avoidance` 는 `:core:kernel` 만 의존(방향 보존). `LanguageCode` 를 food→kernel 로 이동해도 방향 보존(food·avoidance 모두 이미 kernel 의존). |
| **IV. Persistence Encapsulation** | ✅ 해당 없음 | 영속 산출물 없음(enum-only). |
| **V. Domain Content Language Policy** | ⚠️ **부분 충돌 — 정당화** | 원칙 V 본문이 "알러지/주의 성분 … **DB 에 저장**"을 명시. 본 기능은 enum 으로 저장(DB 아님). **실질 규범(ko 원문 + 9개 대상 언어 사전 번역, ko 폴백, 콘텐츠↔UI 분리)은 충족**. 충돌은 저장 *매체*("DB") 한정 → §Complexity 에 정당화, 원칙 V 범위 명확화는 후속 개정으로. 자세한 근거는 [research.md](./research.md) D-PRINV. |

**게이트 결론**: 진행 가능. 유일한 마찰(원칙 V "DB" 문구)은 정당화 가능한 의도적 일탈로 §Complexity 에 기록하며, 안전 직결 데이터의 다국어/폴백 **실질 요구는 모두 만족**한다.

## Project Structure

### Documentation (this feature)

```text
specs/004-avoidance-catalog/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — enum/번역 저장/LanguageCode 이동/원칙 V 결정
├── data-model.md        # Phase 1 — enum 구조·불변식·번역 모델
├── quickstart.md        # Phase 1 — 소비 측 사용 예(api·batch)
├── contracts/
│   └── avoidance-catalog-api.md         # 공개 API 계약(타입 시그니처)
└── tasks.md             # /speckit-tasks 산출(본 명령 아님)
```

### Source Code (repository root)

```text
# 카탈로그 — 소유 컨텍스트 avoidance
core/avoidance/src/main/kotlin/com/meogo/core/avoidance/
├── AvoidanceCategory.kt              # enum 3종(ALLERGEN/DIETARY_RULE/PERSONAL_AVOIDANCE)
├── AvoidanceSubstance.kt             # enum 81종(categories: Set<AvoidanceCategory>, koName)
├── AvoidanceSubstanceTranslations.kt # Map<AvoidanceSubstance, Map<LanguageCode,String>> (mock)
└── AvoidanceCatalog.kt               # resolver: displayName(substance,lang) ko 폴백 · byCategory 등

core/avoidance/src/test/kotlin/com/meogo/core/avoidance/
├── AvoidanceSubstanceTest.kt         # 불변식: 81종·분류 1~3·코드 유일·ko 비공백·분류 도메인
└── AvoidanceCatalogTest.kt           # resolver: 번역 제공/ko 폴백/완전성

# 공유 vocabulary — kernel 로 이동
core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/
└── LanguageCode.kt                   # ← core/food 에서 이동(food·avoidance 공용)
core/kernel/src/test/kotlin/com/meogo/core/kernel/lang/
└── LanguageCodeTest.kt               # ← core/food 테스트에서 이동

# LanguageCode 이동에 따른 import 경로 갱신(동작 불변):
core/food/.../FoodRepository.kt
infra/persistence/.../food/FoodRepositoryAdapter.kt
application/client/.../food/usecase/LanguageResolver.kt
application/client/.../food/usecase/GetFoodDetailUseCase.kt
```

**Structure Decision**: 카탈로그는 **소유 컨텍스트 `:core:avoidance`** 에, 공유 `LanguageCode` 는 **`:core:kernel`** 로 이동(+4개 소비 모듈 import 경로 갱신, 동작 불변). 신규 Gradle 모듈·영속·web·application 유스케이스·평가 로직 없음. 소비처 배선(batch 프롬프트·application 조합·판정)은 후속 — 본 기능은 카탈로그 데이터만 만든다. `:core:avoidance` 는 이미 `api(:core:kernel)` 이라 빌드 변경 불필요.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 V "DB 에 저장" 미준수(enum 으로 저장) | (1) 읽기 전용·런타임 불변 81종 고정 taxonomy 라 DB 동적성이 불필요. (2) `app:batch` LLM 프롬프트와 평가(application)가 **동일 코드·분류를 참조**해야 하며(ADR-0008), `:core:avoidance` enum 이 단일 출처·드리프트 0(안전 직결). (3) DB 시드는 마이그레이션·정합 테스트·런타임 로딩을 더하는데 이득이 없다. | DB 테이블+Flyway 시드: 동적 CRUD 가 없어 과설계. 소비자가 같은 행을 읽어도 **코드 레벨 enum 이 컴파일 타임 정합**을 주는 반면 DB 는 런타임 정합만. 안전 직결 데이터엔 컴파일 보장이 더 강함. **실질 언어 규범(ko+9·폴백)은 enum 으로도 충족**하므로 원칙 V 의 목적(모국어 안전 정보)은 유지. |

> 이 일탈은 원칙 V 의 *목적*(외국인에게 모국어 안전 정보)을 해치지 않고 *매체* 만 다르다. 원칙 V 범위 명확화("고정 reference taxonomy 는 컴파일 enum 저장 허용") + ADR-0008 모듈명 동기화는 **별도 `/speckit-constitution` 개정**으로 후속 처리한다(본 plan 범위 밖, governance 준수).
