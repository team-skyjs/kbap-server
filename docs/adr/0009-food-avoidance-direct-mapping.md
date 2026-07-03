# 0009. 음식↔기피성분 직접 매핑 — 레시피/재료 모델 제거

- **상태**: Accepted
- **날짜**: 2026-07-04
- **관련**: specs/kb-40-food-avoidance-substance-mapping, Jira KB-40, [ADR-0008](./0008-modular-monolith-shared-domain.md) · [ADR-0004](./0004-research-bounded-context.md)

## Context

기존 food 컨텍스트는 음식의 회피·주의 정보를 **재료(레시피) 경유**로 표현했다 — `food`→`food_ingredient`→`ingredient`→`ingredient_avoidance_substance`→`avoidance_substance`. 음식이 어떤 기피성분을 포함하는지 알려면 재료 그래프를 두 단계 조인해야 했다.

그러나:

- 실제 안전 판정·화면 표시에 필요한 정보는 "이 음식이 어떤 기피성분을 어느 정도 확률로 포함하는가"이지, 재료 목록 자체가 아니다.
- 재료를 경유하면 조인 단계가 늘고(N+1·불필요 로딩 위험), `ingredient`/`ingredient_name_translation`/`ingredient_avoidance_substance` 등 관리·번역 대상 테이블이 함께 늘어난다.
- 재료 단계는 도메인 어휘로도 과했다 — 음식 상세 응답은 재료가 아니라 기피성분을 노출한다.

## Decision

**레시피/재료 모델을 제거하고 음식↔기피성분을 직접 매핑한다.**

- 신규 junction `food_avoidance_substance(food_id, substance_code, inclusion_percent)` — `substance_code` 는 `avoidance_substance(code)` 를 FK 로 직접 참조하고, `inclusion_percent` 는 **포함 확률(1~100)** 이다. `(food_id, substance_code)` 조합 유일.
- 도메인 `Food` 는 `avoidanceSubstances: List<FoodAvoidanceSubstance>` 를 갖고, `FoodAvoidanceSubstance(substanceCode: String, inclusionProbability: Int)` 는 **`AvoidanceSubstanceCode` enum 을 import 하지 않고 String 코드로만 참조**한다(헌법 II — 컨텍스트 소유권: 기피성분 코드는 `:core:avoidance` 소유). 표시명 해석은 application 이 `AvoidanceSubstanceRepository.findByCodes(...)` 로 avoidance 카탈로그(`code`·`korean_name`·`translations` JSON)를 조회해 수행한다(ko 폴백).
- **음식 상세 API 응답 계약은 동결한다** — 외부 JSON 키 `payload.ingredients[].{name,iconRef,inclusionPercent,riskStatus}` 를 유지한다. 내부 의미만 재료→포함 기피성분, `inclusionPercent`=포함 확률(1~100)로 바뀐다(클라이언트 무변경).
- 시드는 기존 `food`→재료→성분 체인을 **collapse** 한다 — `food_ingredient ⋈ ingredient_avoidance_substance ⋈ avoidance_substance` 의 ACTIVE 조합을 `(food_id, substance_code)` 로 평탄화하고 확률은 **100** 으로 이행(재료 단계 확률은 소실). 이행 후 `food_ingredient`·`ingredient_avoidance_substance`·`ingredient_name_translation`·`ingredient` 를 DROP 한다.

제거 대상: 도메인 `FoodIngredient`·`Ingredient`, port `IngredientAvoidanceSubstanceRepository`, 관련 JPA 엔티티/리포지토리/어댑터, application `FoodAvoidanceSubstanceResolver`.

## Alternatives Considered

- **재료 모델 유지 + 조회만 최적화**: 재료 그래프는 유지하되 fetch join·batch 조회로 N+1 만 회피. 그러나 재료 단계가 응답·안전 판정에 실질 가치가 없어 관리 비용(테이블·번역·시드)만 남는다.
- **junction 이 `substance_id`(BIGINT FK) 보유**: `avoidance_substance(id)` 참조. 그러나 표시·매핑에 항상 `code` 가 필요해 추가 조인이 생긴다. `substance_code` 를 직접 보유하면 fetch join 1회로 code 까지 확보(추가 조인 0)라 선택.

## Consequences

**+**
- 조회 단순화 — `findByKoreanName` 이 `food` + `food_avoidance_substance` 를 **fetch join 1회**로 로드(성분 개수 무관 상수 쿼리). junction 이 code 를 직접 보유해 avoidance 조인 불필요.
- 테이블·도메인 타입 대폭 축소(재료 4테이블·2도메인·다수 JPA 제거) — 관리·번역 표면 감소.
- 도메인 경계 정합 — 음식 상세가 노출하는 개념(기피성분)과 저장 모델이 일치, 헌법 II(컨텍스트 소유권) 준수.

**−**
- 시드 이행 시 재료 단계 포함 확률이 소실돼 전부 100 으로 수렴(정밀 확률은 후속 데이터 작업 필요).
- `avoidance_substance` 에 없는 `substance_code` 는 FK 로 차단되지만, 런타임에 카탈로그 미존재 코드를 만나면 표시명 폴백이 아니라 **무결성 오류로 처리**(enum label 로 때우지 않음 — 헌법 V).

## 후속

- 정밀 포함 확률 재적재(현재 100 일괄)는 데이터 파이프라인 후속 과제.
- batch(research) 가 미스 메뉴에 기피성분을 채우는 경로는 같은 `food_avoidance_substance` 를 공유(ADR-0008 단일 소스).
