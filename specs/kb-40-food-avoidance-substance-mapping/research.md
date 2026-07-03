# Phase 0 Research: 음식별 기피 성분 직접 매핑

명세와 사용자 지시("상세조회 API 응답 필드 유지")를 근거로 한 설계 결정을 정리한다. 모든 NEEDS CLARIFICATION 은 아래에서 해소된다.

## D1. 음식 상세 응답 계약 처리 — 필드 동결 + 데이터 원천 교체

- **Decision**: `FoodDetailResponse`의 JSON 구조를 **그대로 동결**한다.
  - 배열 키 `ingredients` 유지, 각 항목 `{ name, iconRef, inclusionPercent, riskStatus }` 유지.
  - 각 필드의 **의미만 재정의**: `name`=기피 성분 표시명(요청 언어), `iconRef`=성분 아이콘(현재 없음 → `null`), `inclusionPercent`=**포함 확률 1~100**, `riskStatus`=mock 위험도.
  - Swagger `@Schema(description/example)` 문구만 새 의미로 갱신(계약 파괴 아님).
- **Rationale**: 사용자가 명시적으로 응답 필드 유지를 요구했다. `inclusionPercent`(Int) 키가 확률(1~100, Int)을 그대로 담을 수 있어 **프런트 코드 변경 없이** 데이터 원천만 이관된다. 배열이 "재료"에서 "포함 기피 성분"으로 바뀌지만 표현 계약은 불변이라 클라이언트 파손이 없다.
- **내부 DTO 는 개명**: `GetFoodDetailResult.ingredients: List<IngredientView>` → `avoidanceSubstances: List<AvoidanceSubstanceView>`(필드 `name/iconRef/inclusionProbability/riskStatus`). 컨트롤러 `FoodDetailResponse.from()`에서 내부 `avoidanceSubstances` → 외부 동결 키 `ingredients`/`inclusionPercent` 로 매핑. 내부를 실체에 맞게 이름지어 self-documenting(주석 금지 규약) 유지하면서 외부만 동결.
- **Alternatives considered**:
  - 응답 키까지 `avoidanceSubstances`/`inclusionProbability`로 개명 → **기각**(사용자 지시 위반, 프런트 파손).
  - 내부 DTO 도 `ingredients` 이름 유지 → **기각**(성분을 ingredient 로 부르는 오해 유발, 주석 없이 자기설명 실패).

## D2. Food 도메인의 기피 성분 참조 방식 — 코드(String) 참조

- **Decision**: Food 도메인은 기피 성분을 **`substanceCode: String`** 로 참조한다. 신규 값 객체 `FoodAvoidanceSubstance(substanceCode: String, inclusionProbability: Int)`.
- **Rationale**: 헌법 II — 도메인 모듈은 서로 의존하지 않고 타 컨텍스트를 **코드로만** 참조한다("food 는 avoidance enum 을 import 하지 않고 코드로 참조"). `:core:food` 는 `:core:avoidance` 를 볼 수 없다(둘 다 kernel 만 의존). 코드는 `AvoidanceSubstanceCode` enum 이름 문자열(예: `"EGG"`)이며, 유효성(81종 소속)은 DB FK 와 카탈로그가 보장한다.
- **표시명 해석은 application 에서**: 유스케이스가 `substanceCode` → `AvoidanceSubstanceCode.valueOf(...)` 로 바꿔 `AvoidanceSubstanceRepository.findByCodes(codes)` 호출 → `displayName(lang)`(ko 폴백)으로 표시명 획득. 컨텍스트 조합은 application 계층이라 헌법 II 준수.
- **Alternatives**:
  - Food 가 `substanceId: Long`(DB id) 참조 → **기각**(코드가 더 안정적 자연키이고 헌법이 "코드 참조" 명시; id 참조 시 카탈로그 조회 포트가 findByIds 필요).
  - Food 가 `AvoidanceSubstanceCode` enum 직접 보유 → **기각**(헌법 II 위반: 컨텍스트 결합).

## D3. `food_avoidance_substance` 참조 컬럼 — substance_code FK

- **Decision**: junction 테이블 `food_avoidance_substance(id, food_id, substance_code, inclusion_percent, status, created_at, updated_at)`.
  - `substance_code VARCHAR(40)` FK → `avoidance_substance(code)`(V5 의 `uq_avoidance_substance_code` unique 존재로 FK 가능).
  - `UNIQUE(food_id, substance_code)` — (음식,성분) 조합 유일(FR-004).
  - `inclusion_percent INT NOT NULL` + `CHECK (inclusion_percent BETWEEN 1 AND 100)`(FR-002/003).
  - `food_id` FK → `food(id)`.
- **Rationale**: 도메인이 코드로 참조하므로 junction 이 code 를 직접 보유하면 **Food 재구성 시 avoidance 테이블 조인이 불필요**(fetch join `food → food_avoidance_substance` 1회로 코드까지 확보 → N+1 없음, SC-005). code 는 안정 자연키라 FK 무결성도 유지.
- **Alternatives**: `substance_id` FK + 재구성 시 avoidance_substance 조인 → **기각**(food 영속 조회가 avoidance 테이블에 조인 결합, 도메인이 원하는 code 를 얻으려 3-way 조인 필요). 기존 `ingredient_avoidance_substance` 는 id 를 썼으나 그 테이블은 삭제 대상이라 일관성 부담 없음.

## D4. 초기 포함 확률 시드값 — 도출 성분은 100

- **Decision**: 기존 데이터 이행 시, `food → food_ingredient → ingredient_avoidance_substance` 를 접어 각 음식의 (distinct) 포함 성분을 도출하고, 그 **포함 확률을 100** 으로 시드한다.
- **Rationale**: 기존 매핑은 "이 재료는 이 성분에 해당"의 **확정 사실**이었고 확률 개념이 없었다. 재료가 성분을 포함하면 그 음식엔 성분이 **확실히** 존재하므로 100(확실)이 의미상 정확하고 결정적이다. 큐레이터가 이후 불확실 포함(예: 육수·가게 편차)을 1~99 로 낮춰 정교화한다. 옛 `inclusion_percent`(재료 함유 비율)는 확률과 의미가 달라 그대로 재사용하지 않는다.
- **Alternatives**: 옛 재료 `inclusion_percent` 의 MAX 를 확률로 이관 → **기각**(비율≠확률, 0 값이면 1~100 위반). 전부 1(최저) → **기각**(도출된 성분은 확정 존재이므로 100 이 정확).

## D5. 재료 계층 제거 — 스키마·코드 삭제 순서

- **Decision**: 재료 관련 자산을 전부 제거한다.
  - **DB(V7, DROP 순서 = FK 역순)**: `food_ingredient` → `ingredient_avoidance_substance` → `ingredient_name_translation` → `ingredient`. (신규 `food_avoidance_substance` 생성·시드를 **DROP 전에** 수행해 시드 소스 보존.)
  - **도메인**: `FoodIngredient`·`Ingredient`(`:core:food`), `IngredientAvoidanceSubstanceRepository`(`:core:avoidance`) 삭제.
  - **영속**: `FoodIngredientJpaEntity`·`IngredientJpaEntity`·`IngredientJpaRepository`·`IngredientNameTranslation{JpaEntity,JpaRepository}`·`IngredientAvoidanceSubstance{JpaEntity,JpaRepository}`·`IngredientAvoidanceSubstanceRepositoryAdapter` 삭제.
  - **application**: `FoodAvoidanceSubstanceResolver`(재료 경유) 삭제, `MockIngredientRiskMarker` → `MockAvoidanceRiskMarker` 개명.
  - **port**: `FoodRepository.findIngredientNameTranslations` 제거.
- **Rationale**: 2026-07-03 사용자 확인 — 소비자가 사라지므로 존치하지 않고 전부 제거. 이중 표현·죽은 시드/번역 제거로 유지비용·혼선 차단(US2, SC-002).
- **주의**: `V7` 은 develop(V1~V6) 기준 다음 번호. 병행 브랜치 `009-avoidance-schema-refactor` 가 먼저 머지돼 V7 을 쓰면 번호 충돌 → 머지 시 재넘버링 필요(리네이밍만, 내용 동일). tasks 에 확인 항목 포함.

## D6. 성분 아이콘(iconRef) 처리 — null 유지, 카탈로그 무변경

- **Decision**: 응답 `iconRef` 필드는 유지하되 값은 `null`(기피 성분 카탈로그에 아이콘 컬럼이 없음).
- **Rationale**: FR-010 — 카탈로그(코드·표시명·번역) 불변. 아이콘 추가는 카탈로그 스키마 변경이라 이번 범위 밖. 필드는 계약 동결로 유지하되 데이터는 없음(nullable 이라 프런트 안전).
- **Alternatives**: `avoidance_substance` 에 `icon_ref` 추가 → **기각/후속**(카탈로그 변경·시드 확장, 별도 태스크로 분리 가능).

## D7. 위험도(riskStatus) — mock 유지

- **Decision**: 현행 mock 위험도 표시를 유지(성분 코드 기준으로 mark). 실제 사용자 회피 프로필 기반 판정은 범위 밖.
- **Rationale**: 명세 Assumptions 및 기존 구현(MockIngredientRiskMarker) 계승. 실제 판정은 별도 기능.

## 미해결 항목

- 없음. 위 결정으로 spec 의 모든 요구가 구현 가능하며 NEEDS CLARIFICATION 잔여 없음.
