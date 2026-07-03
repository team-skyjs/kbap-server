# Phase 1 Data Model: 기피 성분 스키마 리팩터

**Date**: 2026-07-03 | **Feature**: 009-avoidance-schema-refactor

변경 전(as-is) → 변경 후(to-be)를 도메인·엔티티·스키마 세 층에서 정리한다.

---

## 1. 도메인 (`:core:avoidance`)

### AvoidanceSubstance (Aggregate Root)

| 필드 | as-is | to-be |
|---|---|---|
| `id: Long` | 유지 | 유지 |
| `code: AvoidanceSubstanceCode` | 유지 | 유지 |
| `koreanName: String` | 유지(불변, `blank` 금지) | 유지 |
| `translations: Map<LanguageCode, String>` | 유지 | 유지 (저장 형태만 영속 계층에서 변경) |
| `categories: Set<AvoidanceCategory>` | **존재** | **제거** |

- **불변식 변화**: `require(categories.isNotEmpty() && categories.size <= 3)` **제거**. `require(koreanName.isNotBlank())` 유지.
- **동작 변화**: `belongsTo(category)` **제거**. `displayName(lang)` **불변**(`ko`→koreanName, else translations[lang] ?: koreanName).
- `reconstitute(...)` 시그니처에서 `categories` 파라미터 **제거**.
- 동등성: `code` 기준 `equals`/`hashCode` 유지.

### AvoidanceCategory (enum) — **삭제**

- `ALLERGEN`/`DIETARY_RULE`/`PERSONAL_AVOIDANCE` 3값. 소비자 없음 → 파일 삭제.

### AvoidanceSubstanceCode (enum) — 불변

- 코드 + 개발 가독성용 `label`(=시드 korean_name) 만 보유. 81종 그대로.

### Port: AvoidanceSubstanceRepository

| 메서드 | as-is | to-be |
|---|---|---|
| `byCategory(category): List<AvoidanceSubstance>` | 존재 | **제거** |
| `findByCodes(codes): List<AvoidanceSubstance>` | 유지 | 유지 |

### Port: IngredientAvoidanceSubstanceRepository — 불변

- `findByIngredientIds(ingredientIds): Map<Long, Set<AvoidanceSubstance>>` 유지.

---

## 2. 영속 엔티티 (`:infra:persistence`)

### AvoidanceSubstanceJpaEntity (`avoidance_substance`)

| 컬럼/필드 | as-is | to-be |
|---|---|---|
| `code` (VARCHAR 40, NN) | 유지 | 유지 |
| `korean_name` (VARCHAR 100, NN) | 유지 | 유지 |
| `name_zh_hans` … `name_es` (VARCHAR 100 × 9, NULL) | **9개 컬럼** | **제거** |
| `translations` (JSON) | 없음 | **신설** — `@JdbcTypeCode(SqlTypes.JSON) var translations: Map<String,String>`(키=LanguageCode.code, 비-ko만), 기본 `{}` |

- `toDomain()`: 이제 `categories` 인자를 받지 않는다. `translations`(Map<String,String>)를 `LanguageCode` 로 변환해 도메인 `Map<LanguageCode,String>` 구성(변환 불가 키는 무시). 빈 맵 허용.
- `BaseEntity` 상속(soft delete `@SQLRestriction("status='ACTIVE'")`) 유지. `kotlin-jpa` no-arg 유지.

### AvoidanceSubstanceCategoryJpaEntity (`avoidance_substance_category`) — **삭제**

- 엔티티·`AvoidanceSubstanceCategoryJpaRepository` 파일 제거.

### AvoidanceSubstanceReconstitutor — 로직 축소

- as-is: `substanceJpaRepository` + `categoryJpaRepository` 조인, 카테고리 없는 성분 drop.
- to-be: `categoryJpaRepository` 의존 제거. `byIds`/`fromRows` 가 `AvoidanceSubstanceJpaEntity` rows 만으로 `toDomain()` 호출해 복원(모든 행 복원, drop 필터 없음).

### AvoidanceSubstanceRepositoryAdapter — 축소

- 생성자에서 `avoidanceSubstanceCategoryJpaRepository` 제거.
- `byCategory` 오버라이드 제거. `findByCodes` 는 그대로(`findByCodeIn` → `Reconstitutor.fromRows`).

### IngredientAvoidanceSubstanceRepositoryAdapter — 불변

- `Reconstitutor.byIds` 재사용. 시그니처·동작 동일(내부적으로 카테고리 조인이 사라져 쿼리만 단순화).

---

## 2.5 `translations` JSON 스키마 (설계 결정)

**결정**: 최상위가 **평면 객체**이고 **언어 code 가 키, 번역 문자열이 값**인 맵 형태로 저장한다. `ko` 는 넣지 않는다(전용 `korean_name` 컬럼).

```json
{ "en": "Egg", "ja": "卵", "zh-Hans": "鸡蛋" }
```

| 항목 | 정의 |
|---|---|
| 키 | `LanguageCode.code`(`en`,`ja`,`zh-Hans`,`zh-Hant`,`vi`,`id`,`th`,`ru`,`es`) — `ko` 제외 |
| 값 | 번역된 성분 이름 문자열 |
| 빈 값 | 번역 없음 → `{}` |
| 누락 언어 | 키 부재(NULL 언어는 키로 넣지 않음) |
| 도메인 복원 | 엔티티 `Map<String,String>` → 엄격 code 매칭으로 `Map<LanguageCode,String>`(매핑 불가 키 무시) |

**왜 객체(코드=키)인가 — 대안 비교**

번역은 "언어당 하나짜리 맵"이라, 언어 code 를 **자연 키**로 쓰는 객체 형태가 가장 맞다.

| 기준 | 객체(코드=키) `{"en":"Egg"}` | 값 래핑 `{"en":{"name":"Egg"}}` | 블록 배열 `[{"lang":"en","name":"Egg"}]` |
|---|---|---|---|
| 도메인(`Map<LanguageCode,String>`) 일치 | **1:1** | 언랩(`["name"]`) 필요 | 배열→맵 변환 필요 |
| 언어 유일성 | **구조가 강제**(키 중복 불가) | 강제 | 미보장(`en` 중복 허용) |
| 조회 | `translations->>'$.en'` 직접 | `$.en.name` | 배열 스캔 필요 |
| 크기 | 최소 | 큼 | 필드명 반복으로 가장 큼 |
| 순서 의미 | 없음(맵) — 적합 | 없음 | 배열이 순서 암시(불필요) |

- **값 래핑/블록 배열은 기각**: 언어별로 이름 외 부가 필드(검수 상태·출처 등)가 필요할 때만 값어치가 있는데, 이 카탈로그는 **고정·읽기전용 reference(81종, 사전 검수)**라 행별 부가 메타가 불필요(YAGNI). 헌법 원칙 V 의 "안전 직결 데이터 검수 상태 구분"은 배치 LLM 동적 콘텐츠 대상이며 본 고정 카탈로그와 무관.
- **향후 확장**: 부가 필드가 실제로 필요해지면 그때 `code → {name, ...}` 로 승격하는 JSON 데이터 마이그레이션으로 충분(컬럼 추가 없이). 지금 미리 감쌀 이유 없음 — 되돌리기 비용 낮음.

---

## 3. 스키마 (Flyway V6 — MySQL)

`V6__drop_avoidance_category_and_jsonify_translations.sql`:

1. `avoidance_substance` 에 `translations JSON NULL` 추가.
2. 기존 `name_*` → `translations` 백필: 비-NULL 언어만 포함하는 JSON 객체 생성(키 = `en`,`ja`,`zh-Hans`,`zh-Hant`,`vi`,`id`,`th`,`ru`,`es`), 전부 NULL 이면 `{}`.
3. `name_zh_hans … name_es` 9개 컬럼 DROP.
4. `avoidance_substance_category` 테이블 DROP.

**관계 영향 없음**: `ingredient_avoidance_substance` 는 `avoidance_substance(id)`·`ingredient(id)` FK 만 가지며 category 테이블을 참조하지 않는다 → category 테이블 DROP 안전.

**테스트 스키마(H2)**: Flyway off + `ddl-auto=create-drop` 이므로 엔티티 매핑에서 `translations JSON` 컬럼과 category 테이블 부재가 자동 반영된다(V6 는 prod 전용).

---

## 4. 데이터 무결성 / 검증 규칙

- **번역 동등성(SC-001)**: 마이그레이션 전 각 성분의 `name_XX` 값 = 마이그레이션 후 `translations['xx']` 값(비-NULL 언어 한정). `displayName(lang)` 결과가 전후 동일.
- **폴백(FR-005)**: `translations` 에 요청 언어 키 없음 → `koreanName` 반환. 빈 `{}` → 모든 비-ko 조회가 koreanName.
- **전 성분 복원(R4)**: 81종 모두 조회됨(카테고리 유무와 무관해짐).
- **시드 정합(원칙 V)**: `AvoidanceSubstanceCode` 코드 집합 = DB `code` 집합, `label` = `korean_name`. (이번 변경으로 깨지지 않음 — code/korean_name 불변.)
