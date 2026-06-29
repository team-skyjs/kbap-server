# Phase 1 Data Model: 회피·주의 성분 카탈로그

저장 매체는 **컴파일 상수(enum + 데이터 객체)** 다. "엔티티"는 DB 테이블이 아니라 Kotlin 타입을 가리킨다. 카탈로그는 **소유 컨텍스트 `:core:avoidance`**(`com.meogo.core.avoidance`), `LanguageCode` 는 **`:core:kernel`**(`com.meogo.core.kernel.lang`). (근거: [research.md](./research.md) D-STORE/D-OWNER/D-LANG/D-TRANS.)

## 타입 개요

```
:core:avoidance  (com.meogo.core.avoidance)
  AvoidanceCategory (enum, 3)
          ▲ Set<>
  AvoidanceSubstance (enum, 81) ── koName, categories
          │
  AvoidanceSubstanceTranslations ── Map<AvoidanceSubstance, Map<LanguageCode, String>>
          │
  AvoidanceCatalog (resolver) ── displayName(substance, lang): String · byCategory(cat): List
          │ uses
:core:kernel  (com.meogo.core.kernel.lang)
  LanguageCode (enum) ── ko + 9 대상 언어  (← core.food 에서 이동)
```

## AvoidanceCategory (enum)

- 값(3, 고정): `ALLERGEN`, `DIETARY_RULE`, `PERSONAL_AVOIDANCE`.
- 의미: 사용자별 판정 시점의 **경고 강도/톤** 결정(ALLERGEN=가장 보수적, DIETARY_RULE=기준 불일치 안내, PERSONAL_AVOIDANCE=부드러운 안내). 경고 *문구* 자체는 본 범위 밖(spec clarify).
- 불변식: 3종 외 값 없음(enum 이 강제).
- 출처 FR: FR-006.

## AvoidanceSubstance (enum, 81종)

- 프로퍼티:
  - `categories: Set<AvoidanceCategory>` — 1~3개, 중복 없음(Set).
  - `koName: String` — ko 원문 명칭, 비공백.
  - `code` = enum `name`(예: `PEANUT`, `PORK`) — 안정적·유일 식별자(대문자 스네이크).
- 불변식(테스트·`init` 강제):
  - 전체 81개(콘텐츠 확정 전엔 mock 개수; 구조 고정).
  - `categories.isNotEmpty() && categories.size <= 3` (FR-002·FR-006, SC-002).
  - `koName.isNotBlank()` (FR-002, edge: ko 빈 성분 불가).
  - 코드 유일성 = enum 보장(SC-004 중복 시드 무의미 — 컴파일 상수).
- 관계: 성분 ↔ 분류는 **다대다**를 `Set<AvoidanceCategory>` 로 표현(성분당 1~3).
- 출처 FR: FR-001, FR-002, FR-005, FR-007.

## AvoidanceSubstanceTranslations (데이터 객체)

- 구조: `Map<AvoidanceSubstance, Map<LanguageCode, String>>`.
- 키: 성분 → (대상 언어 → 번역 명칭). **ko 는 여기 넣지 않는다**(원문은 `koName` 에서; 음식명/재료명 정책과 동일, FR-003).
- 대상 언어: `zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es`(9종, ADR-0003).
- 현재 mock — 확정 값 수령 시 통째 교체.
- 출처 FR: FR-003.

## AvoidanceCatalog (resolver, avoidance)

- `displayName(substance: AvoidanceSubstance, lang: LanguageCode): String`
  - `lang == KO` → `substance.koName`.
  - else → 번역 Map 에 있으면 그 값, **없으면 `substance.koName` 으로 폴백**(FR-004, edge: 미보유 언어 → ko).
  - 빈 명칭 반환 0(SC-003).
- `byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>` — 해당 분류에 속한 성분들(복수 분류 성분 포함).
- (선택) `all(): List<AvoidanceSubstance>` = `AvoidanceSubstance.entries`.
- 순수 함수·무 I/O·무 상태.

## LanguageCode (enum, kernel.lang — 이동)

- 기존 `com.meogo.core.food.LanguageCode` 와 **값·동작 동일**: `KO("ko")` + 9개 대상 언어, `from(code): LanguageCode`(미지정 → KO).
- 위치만 `com.meogo.core.kernel.lang.LanguageCode` 로 이동(D-LANG). food/persistence/application import 경로 갱신.

## 요구사항 ↔ 모델 매핑

| FR/SC | 반영 |
|-------|------|
| FR-001 81종 | `AvoidanceSubstance` enum 항목 수 |
| FR-002 코드+1~3분류+ko | enum `name`/`categories`/`koName` + `init` 불변식 |
| FR-003 9개 번역 | `AvoidanceSubstanceTranslations` |
| FR-004 ko 폴백 | `AvoidanceCatalog.displayName` |
| FR-005 코드 유일 | enum 보장 |
| FR-006 분류 3종 고정·복수 | `AvoidanceCategory` + `Set` |
| FR-007 시드 | enum/Map 선언 자체가 "시드"(컴파일 상수) |
| FR-008 읽기 전용 | enum/`object` — 변경 경로 없음 |
| SC-001~005 | 불변식·완전성·폴백 단위 테스트로 검증 |
