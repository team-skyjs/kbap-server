# Food Detail Database Design

음식 상세 조회 API 기준으로 현재 DB 테이블 설계, 다국어 데이터 저장 방식, N+1 회피 방식을 정리한다.

> 2026-07-04 개정(Jira KB-40, spec `kb-40-food-avoidance-substance-mapping`, [ADR-0009](../adr/0009-food-avoidance-direct-mapping.md)): 재료(레시피) 모델(`food_ingredient`·`ingredient`·`ingredient_name_translation`)을 제거하고 **음식↔기피성분 직접 매핑(`food_avoidance_substance`)** 으로 전환했다. 아래 내용은 새 모델 기준이다.

## 현재 테이블 구조

현재 설계는 **한국어 원문은 기준 테이블에 저장하고, 9개 대상 언어 번역은 별도 번역 테이블(또는 카탈로그의 `translations` JSON)에 저장**하는 방식이다. `ko`는 번역 쪽에 중복 저장하지 않는다.

| 테이블 | 역할 | 주요 키/제약 |
| --- | --- | --- |
| `food` | 음식 기준 정보. 한국어 음식명, 이미지, 한국어 간단/상세 설명 저장 | `korean_name` unique |
| `food_avoidance_substance` | 음식과 기피성분의 직접 연결. 포함 확률(1~100) 저장 | `substance_code` FK→`avoidance_substance(code)`, `(food_id, substance_code)` unique, `inclusion_percent` CHECK 1~100 |
| `avoidance_substance` | 기피성분 카탈로그(표시명 원천). 코드, 한국어명, 번역 JSON | `code` unique, `translations` JSON(키=`LanguageCode.code`, 비-ko) |
| `food_name_translation` | 음식명 번역 | `(food_id, lang_code)` unique |
| `food_description_translation` | 음식 설명 번역. `BRIEF`, `DETAILED` 구분 | `(food_id, kind, lang_code)` unique |

지원 번역 언어는 `zh-Hans`, `en`, `ja`, `zh-Hant`, `vi`, `id`, `th`, `ru`, `es`다.

기피성분 표시명은 별도 번역 테이블이 아니라 `avoidance_substance` 카탈로그에서 해석한다 — `ko`는 `korean_name`, 그 외 언어는 `translations` JSON 에서 꺼내고, 값이 없으면 `korean_name`(ko)으로 fallback한다.

## 음식 상세 조회에서 사용하는 데이터

`GET /api/v1/foods/detail`은 현재 별도 캐시 계층 없이 요청마다 DB를 조회한다.

| 요청 언어 | 조회 데이터 | 설명 |
| --- | --- | --- |
| `ko` | `food`, `food_avoidance_substance`, `avoidance_substance` | 한국어 원문은 기준/카탈로그 테이블에 있으므로 음식명·설명 번역 테이블을 조회하지 않는다. |
| `ko` 외 지원 언어 | 위 기본 조회 + `food_name_translation`, `food_description_translation` | 표시용 음식명, 설명만 요청 언어로 바꾼다. 성분 표시명은 카탈로그의 `translations`에서 해석한다. 번역이 없으면 한국어 원문으로 fallback한다. |

현재 조회 흐름은 다음과 같다.

1. `food.korean_name`으로 음식 1건을 찾는다. 같은 쿼리에서 `food_avoidance_substance`를 fetch join으로 함께 가져온다(junction이 `substance_code`를 직접 보유해 `avoidance_substance` 추가 조인은 불필요).
2. 포함 기피성분은 도메인에서 `inclusionProbability`(포함 확률) 내림차순으로 정렬한다.
3. application이 성분 코드 집합을 `AvoidanceSubstanceRepository.findByCodes(...)`로 카탈로그 조회해 요청 언어 표시명을 해석한다(ko 폴백 내장).
4. 요청 언어가 `ko`가 아니면 음식명, 설명 번역을 추가 조회한다.
5. 번역이 없는 필드는 한국어 원문을 사용한다.
6. 현재 위험도는 실제 사용자 회피 조건 기반 판정이 아니라 mock marker로 표시한다.

## 다국어 저장 방식 선택지

### 선택지 A: 기준 테이블에 언어별 컬럼을 모두 넣기

예시:

```text
food(
  id,
  korean_name,
  name_en,
  name_ja,
  name_zh_hans,
  brief_description_en,
  brief_description_ja,
  ...
)
```

장점:

- 음식 1건 조회 시 join이 줄어든다.
- 지원 언어가 절대 늘지 않고, 번역 필드 종류도 거의 변하지 않는다면 구현이 단순하다.
- 관리자 화면이나 단순 조회에서는 한 row만 보면 되어 직관적이다.

단점:

- 언어가 추가될 때마다 스키마 변경이 필요하다.
- 음식명, 설명처럼 번역 대상이 늘수록 컬럼 수가 빠르게 커진다.
- 특정 언어만 누락됐는지, 어떤 언어가 검수됐는지 같은 번역 단위 상태를 표현하기 어렵다.

### 선택지 B: 번역 테이블/카탈로그 JSON을 별도로 두기

현재 구현 방식이다. 음식명·설명은 **별도 번역 테이블**, 기피성분 표시명은 **카탈로그의 `translations` JSON**으로 둔다.

예시:

```text
food(id, korean_name, brief_description, detailed_description, ...)
food_name_translation(food_id, lang_code, name)
food_description_translation(food_id, kind, lang_code, content)
avoidance_substance(code, korean_name, translations JSON)
```

장점:

- 언어 추가 시 테이블 스키마를 바꾸지 않고 row(또는 JSON 키)만 추가하면 된다.
- 번역 대상별 unique 제약을 명확하게 둘 수 있다.
- 음식명, 설명, 성분명처럼 성격이 다른 번역을 분리해 관리하기 쉽다.
- 특정 언어/필드의 번역 누락, 검수 상태, 출처 같은 메타데이터를 나중에 붙이기 쉽다.
- 한국어 원문과 번역 데이터를 명확히 구분할 수 있다.

단점:

- 조회 쿼리가 여러 번 발생한다(음식명·설명 번역).
- 조회 로직에서 fallback 처리가 필요하다.
- 단순히 한 음식의 모든 표시 데이터를 보고 싶을 때 기준 테이블만으로는 부족하다.

## 현재 설계 판단

현재 요구사항에는 **별도 번역 테이블/카탈로그 JSON 방식이 더 맞다**.

이유:

- 이미 한국어 원문 + 9개 대상 언어를 다루고 있어 언어 수가 적지 않다.
- 번역 대상이 음식명뿐 아니라 음식 설명, 기피성분명까지 분리되어 있다.
- 향후 설명 종류, 검수 상태, 번역 출처, 언어 추가 가능성이 있다.
- 기피성분은 여러 음식에서 공유되므로 성분 표시명을 `food` row에 넣으면 중복이 커진다 — 카탈로그(`avoidance_substance`)가 단일 원천이다.

다만 멘토님께 질문할 때는 다음 기준으로 확인하면 좋다.

- 지원 언어가 앞으로 고정인지, 더 늘어날 가능성이 있는지
- 번역별 검수 상태나 출처를 저장할 계획이 있는지
- 음식 상세 조회 성능 목표가 어느 정도인지
- 관리자/운영 화면에서 번역 데이터를 어떤 단위로 수정할지

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

이 쿼리로 `food` 1건과 연결된 `food_avoidance_substance`를 함께 가져온다. junction이 `substance_code`를 직접 보유하므로 `avoidance_substance`를 추가로 조인하지 않아도 성분 코드까지 확보된다. 따라서 `FoodJpaEntity.toDomain()`에서 `foodAvoidanceSubstances.map { it.toDomain() }`을 호출해도 성분마다 추가 select가 발생하지 않는다.

### 2. 기피성분 표시명 조회

성분 표시명은 성분마다 한 번씩 조회하지 않고, 성분 코드 집합으로 카탈로그를 한 번에 조회한다.

```kotlin
avoidanceSubstanceRepository.findByCodes(codes)
```

조회 결과는 `AvoidanceSubstanceCode -> AvoidanceSubstance`로 바꾼 뒤, 각 성분의 `displayName(lang)`(ko 폴백 내장)으로 표시명을 조립한다.

### 3. 음식 설명 번역 조회

음식 설명 번역은 음식 1건 기준으로 `BRIEF`, `DETAILED`를 한 번에 조회한다.

```kotlin
findByFoodIdAndLangCode(foodId, lang.code)
```

조회 결과는 `FoodDescriptionKind -> content` map으로 바꾼 뒤, 필요한 설명 필드에 매핑한다.

### 4. 음식명 번역 조회

음식명 번역은 음식 상세 조회가 음식 1건을 대상으로 하므로 단건 조회다.

```kotlin
findByFoodIdAndLangCode(foodId, lang.code)
```

## 현재 쿼리 수 관점

음식 상세 조회 1회 기준으로 대략 다음과 같이 동작한다.

| 요청 | 쿼리 성격 |
| --- | --- |
| `lang = ko` | 음식 + 포함 기피성분 fetch join 1회, 성분 카탈로그 조회 1회 |
| `lang != ko` | 위 2회 + 음식명 번역 1회, 음식 설명 번역 1회 |

즉 성분 개수에 따라 쿼리 수가 늘어나는 구조는 아니다. 성분이 3개든 20개든 fetch join 1회로 로드하고, 표시명 카탈로그는 `findByCodes` 1회로 처리한다.

## 남아 있는 고려사항

- 현재는 음식 상세 조회마다 DB를 조회한다. 자주 조회되는 음식은 추후 application cache 또는 DB query cache를 검토할 수 있다.
- `food_description_translation`은 `kind`로 설명 종류를 구분한다. 설명 종류가 더 늘어날 수 있다면 현재 구조가 유리하다.
- `food_avoidance_substance.inclusion_percent`(포함 확률)는 현재 시드 이행 시 100 일괄이다(재료 단계 확률 소실). 정밀 확률 재적재는 데이터 파이프라인 후속 과제다.
- 기피성분 표시명은 `avoidance_substance` 카탈로그가 단일 원천이므로, 성분 번역 검수·출처 메타데이터는 카탈로그 쪽에서 관리한다.
