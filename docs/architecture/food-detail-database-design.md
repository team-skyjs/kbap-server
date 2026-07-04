# Food Detail Database Design

음식 상세 조회 API 기준으로 현재 DB 테이블 설계, 다국어 데이터 저장 방식, N+1 회피 방식을 정리한다.

> 2026-07-04 개정(Jira KB-40, spec `kb-40-food-avoidance-substance-mapping`, [ADR-0009](../adr/0009-food-avoidance-direct-mapping.md)): 재료(레시피) 모델(`food_ingredient`·`ingredient`·`ingredient_name_translation`)을 제거하고 **음식↔기피성분 직접 매핑(`food_avoidance_substance`)** 으로 전환했다.
>
> 2026-07-05 개정(Jira KB-48, spec `kb-48-food-translation-json-column`): 음식명·설명 번역을 별도 테이블(`food_name_translation`·`food_description_translation`)에서 **`food` 행의 JSON 칼럼 2개(`name_translations`·`description_translations`)** 로 이관했다(기피성분 `avoidance_substance.translations` 와 동형). 두 번역 테이블은 무손실 백필 후 DROP(마이그레이션 V10). 아래 내용은 이 새 모델 기준이다.

## 현재 테이블 구조

현재 설계는 **한국어 원문은 기준 테이블(`food`)에 저장하고, 9개 대상 언어 번역은 같은 행의 JSON 칼럼(또는 카탈로그의 `translations` JSON)에 저장**하는 방식이다. `ko`는 번역 JSON 에 중복 저장하지 않는다.

| 테이블 | 역할 | 주요 키/제약 |
| --- | --- | --- |
| `food` | 음식 기준 정보. 한국어 음식명·이미지·한국어 설명·맵기, 그리고 음식명/설명의 대상 언어 번역 JSON | `korean_name` unique, `name_translations`·`description_translations` JSON NOT NULL(키=`LanguageCode.code`, 비-ko), `spiciness` CHECK 0~10 |
| `food_avoidance_substance` | 음식과 기피성분의 직접 연결. 포함 확률(1~100) 저장 | `substance_code` FK→`avoidance_substance(code)`, `(food_id, substance_code)` unique, `inclusion_percent` CHECK 1~100 |
| `avoidance_substance` | 기피성분 카탈로그(표시명 원천). 코드, 한국어명, 번역 JSON | `code` unique, `translations` JSON(키=`LanguageCode.code`, 비-ko) |

지원 번역 언어는 `zh-Hans`, `en`, `ja`, `zh-Hant`, `vi`, `id`, `th`, `ru`, `es`다.

음식명·설명 번역은 `food` 행의 JSON 에서, 기피성분 표시명은 `avoidance_substance` 카탈로그에서 해석한다 — 세 경우 모두 `ko`는 원문 컬럼(`korean_name`·`description`), 그 외 언어는 JSON 에서 꺼내고 값이 없으면 한국어 원문으로 fallback한다.

## 음식 상세 조회에서 사용하는 데이터

`GET /api/v1/foods/detail`은 현재 별도 캐시 계층 없이 요청마다 DB를 조회한다.

| 요청 언어 | 조회 데이터 | 설명 |
| --- | --- | --- |
| `ko` | `food`, `food_avoidance_substance`, `avoidance_substance` | 한국어 원문은 `food` 기준 컬럼에 있으므로 번역 JSON 을 보지 않는다. |
| `ko` 외 지원 언어 | 위와 **동일**(추가 테이블 조회 없음) | 음식명·설명 번역이 `food` 행 JSON 에 함께 로드되므로 언어별 별도 조회가 없다. 성분 표시명은 카탈로그 `translations`에서 해석한다. 번역이 없으면 한국어 원문으로 fallback한다. |

현재 조회 흐름은 다음과 같다.

1. `food.korean_name`으로 음식 1건을 찾는다. 같은 쿼리에서 `food_avoidance_substance`를 fetch join으로 함께 가져온다. 음식명·설명 번역 JSON 은 `food` 행 컬럼이라 이 조회에 포함된다.
2. 포함 기피성분은 도메인에서 `inclusionProbability`(포함 확률) 내림차순으로 정렬한다.
3. application이 성분 코드 집합을 `AvoidanceSubstanceRepository.findByCodes(...)`로 카탈로그 조회해 요청 언어 표시명을 해석한다(ko 폴백 내장).
4. 음식명·설명은 도메인 `FoodContent.name(lang)`/`description(lang)`으로 해석한다 — 요청 언어 번역이 있으면 그 값, 없거나 `ko`면 한국어 원문(추가 DB 조회 없음).
5. 현재 위험도는 실제 사용자 회피 조건 기반 판정이 아니라 mock marker로 표시한다.

## 다국어 저장 방식 선택지

### 선택지 A: 기준 테이블에 언어별 컬럼을 모두 넣기

예시:

```text
food(id, korean_name, name_en, name_ja, name_zh_hans, description_en, description_ja, ...)
```

장점:

- 음식 1건 조회 시 join이 줄어든다.
- 지원 언어가 절대 늘지 않고, 번역 필드 종류도 거의 변하지 않는다면 구현이 단순하다.

단점:

- 언어가 추가될 때마다 스키마 변경이 필요하다.
- 음식명, 설명처럼 번역 대상이 늘수록 컬럼 수가 빠르게 커진다.

### 선택지 B: 별도 번역 테이블(언어당 1행)

예시:

```text
food(id, korean_name, description, ...)
food_name_translation(food_id, lang_code, name)
food_description_translation(food_id, lang_code, content)
```

장점:

- 번역별 unique 제약·검수 상태·출처 같은 메타데이터를 row 단위로 붙이기 쉽다.

단점:

- 비-ko 조회 시 음식명·설명 번역을 언어별로 추가 조회한다(쿼리 수 증가).
- 조회 로직에서 fallback 처리가 필요하다.

### 선택지 C: 기준 행에 번역 JSON 칼럼(현재 구현)

현재 구현 방식이다(KB-48). 음식명·설명 번역을 `food` 행의 `name_translations`·`description_translations` JSON 칼럼에, 기피성분 표시명을 카탈로그 `avoidance_substance.translations` JSON 에 둔다.

```text
food(id, korean_name, description, spiciness, name_translations JSON, description_translations JSON, ...)
avoidance_substance(code, korean_name, translations JSON)
```

장점:

- 언어 추가 시 스키마 변경 없이 JSON 키만 추가한다.
- 번역이 기준 행에 함께 있어 **비-ko 조회에도 언어별 추가 쿼리가 없다**(음식 1건 로드로 번역까지 확보).
- 한국어 원문(`korean_name`·`description`)과 번역(JSON, 비-ko)을 명확히 구분한다.

단점:

- 번역별 unique/CHECK 제약·검수 상태를 DB로 강제하기 어렵다(앱 계층으로 이동).
- 번역 단위 메타데이터(검수 상태·출처)를 붙이려면 JSON 구조를 확장해야 한다.

## 현재 설계 판단

현재 요구사항에는 **기준 행 JSON 칼럼 방식(선택지 C)이 맞다**.

이유:

- 지원 언어(9종)가 고정에 가깝고 언어당 행 메타데이터가 당장 필요 없다.
- 음식명·설명 번역이 음식 1건과 1:1이라 기준 행에 함께 두는 것이 조회에 유리하다(추가 쿼리 0).
- 기피성분 표시명은 여러 음식이 공유하므로 `food` 행에 넣으면 중복이 커진다 — 카탈로그(`avoidance_substance`)가 단일 원천으로 남는다.
- 결과적으로 음식명·설명·성분명 세 번역이 모두 **`언어→문자열` JSON + ko 폴백**이라는 동일 패턴으로 수렴한다.

향후 번역별 검수 상태·출처가 필요해지면 선택지 B(별도 테이블)로의 회귀 또는 JSON 스키마 확장을 재검토한다.

## N+1 문제 회피 방식

현재 음식 상세 조회에서 주의할 수 있는 N+1 지점은 두 가지다.

1. 음식 1건을 가져온 뒤 포함 기피성분 목록을 순회하면서 `food_avoidance_substance`를 개별 조회하는 문제
2. 성분별 표시명을 성분 개수만큼 반복 조회하는 문제

현재 구현은 두 지점을 모두 피하도록 되어 있다.

### 1. 음식 + 포함 기피성분 조회

`FoodJpaRepository.findByKoreanNameWithAvoidanceSubstances`는 fetch join을 사용한다.

```sql
select distinct f from FoodJpaEntity f
left join fetch f.foodAvoidanceSubstances
where f.koreanName = :koreanName
```

이 쿼리로 `food` 1건(음식명·설명 번역 JSON 컬럼 포함)과 연결된 `food_avoidance_substance`를 함께 가져온다. junction이 `substance_code`를 직접 보유하므로 `avoidance_substance`를 추가로 조인하지 않아도 성분 코드까지 확보된다.

### 2. 기피성분 표시명 조회

성분 표시명은 성분마다 한 번씩 조회하지 않고, 성분 코드 집합으로 카탈로그를 한 번에 조회한다.

```kotlin
avoidanceSubstanceRepository.findByCodes(codes)
```

조회 결과는 `AvoidanceSubstanceCode -> AvoidanceSubstance`로 바꾼 뒤, 각 성분의 `displayName(lang)`(ko 폴백 내장)으로 표시명을 조립한다.

### 3. 음식명·설명 번역 조회

음식명·설명 번역은 `food` 행의 JSON 칼럼이라 **별도 조회가 없다**. 음식 1건 로드 시 함께 온 `name_translations`·`description_translations`가 도메인 `FoodContent`로 복원되고, `name(lang)`/`description(lang)`이 요청 언어 값 또는 ko 원문을 반환한다.

## 현재 쿼리 수 관점

음식 상세 조회 1회 기준으로 대략 다음과 같이 동작한다.

| 요청 | 쿼리 성격 |
| --- | --- |
| `lang = ko` | 음식(+번역 JSON) + 포함 기피성분 fetch join 1회, 성분 카탈로그 조회 1회 |
| `lang != ko` | **동일**(음식명·설명 번역이 음식 행에 포함되어 추가 쿼리 없음) |

즉 성분 개수·요청 언어와 무관하게 상수 쿼리다. 성분이 3개든 20개든 fetch join 1회로 로드하고, 표시명 카탈로그는 `findByCodes` 1회로 처리한다. (KB-48 이전에는 비-ko 요청마다 음식명·설명 번역 조회가 2회 추가됐다.)

## 남아 있는 고려사항

- 현재는 음식 상세 조회마다 DB를 조회한다. 자주 조회되는 음식은 추후 application cache 또는 DB query cache를 검토할 수 있다.
- 번역 JSON 은 lang_code 화이트리스트·`(food_id, lang_code)` 유일성을 DB로 강제하지 못한다(앱 계층에서 보장). 번역 검수 상태·출처 같은 메타데이터가 필요해지면 JSON 구조 확장 또는 별도 테이블을 재검토한다.
- `food_avoidance_substance.inclusion_percent`(포함 확률)는 V9 에서 10종 시드에 실제값을 채웠다. 그 밖의 음식 정밀 확률 재적재는 데이터 파이프라인 후속 과제다.
- 기피성분 표시명은 `avoidance_substance` 카탈로그가 단일 원천이므로, 성분 번역 검수·출처 메타데이터는 카탈로그 쪽에서 관리한다.
