# Research: 음식 기피성분 JSON 컬럼 이관

## R1. JSON 컬럼 매핑 방식

- **Decision**: `Food` 엔티티에 `@JdbcTypeCode(SqlTypes.JSON)` + `List<FoodAvoidanceItem>` 프로퍼티로 매핑한다. `FoodAvoidanceItem` 은 food 모듈 `model/` 의 data class 값 객체.
- **Rationale**: `Food` 는 이미 `nameTranslations`·`descriptionTranslations` 를 같은 방식(`@JdbcTypeCode(SqlTypes.JSON)`)으로 매핑하고 있다 — 검증된 기존 패턴 재사용. 별도 `AttributeConverter` 를 만들 이유가 없다.
- **Alternatives considered**: `AttributeConverter<List<…>, String>` 수동 직렬화(기존 패턴 대비 코드만 늘어남 — 기각), `@ElementCollection` 별도 테이블(테이블을 없애는 게 목적 — 기각).

## R2. JSON 키 네이밍 (`inclusion_percent`)

- **Decision**: Jira KB-210 이 명세한 JSON 형태(`{"code": "SOY", "inclusion_percent": 34}`)를 그대로 따른다. Kotlin 프로퍼티는 `inclusionPercent`, 직렬화 키는 `@JsonProperty("inclusion_percent")` 로 고정한다.
- **Rationale**: 백필 SQL(`JSON_OBJECT('code', …, 'inclusion_percent', …)`)이 쓰는 키와 Hibernate 역직렬화 키가 반드시 일치해야 한다. Hibernate 6 의 JSON FormatMapper 는 classpath 의 Jackson 을 사용하므로 `@JsonProperty` 가 적용된다.
- **Alternatives considered**: camelCase(`inclusionPercent`) 키로 백필 SQL 을 맞추기 — Jira 명세와 어긋남, 기각.
- **검증 장치**: 마이그레이션이 만든 JSON 을 엔티티가 읽는 왕복을 Testcontainers 통합 테스트로 확인한다(키 불일치 시 즉시 실패).

## R3. 컬럼 정의 — NULL 허용과 제약

- **Decision**: `avoidance_substances JSON NOT NULL`. 단 MySQL 은 non-empty 테이블에 NOT NULL JSON 컬럼을 바로 추가할 수 없으므로(기본값 불가) 마이그레이션 안에서 3단계로 처리: ① NULL 로 추가 → ② 백필(매핑 없는 음식은 `JSON_ARRAY()`) → ③ `MODIFY … NOT NULL`. CHECK·UNIQUE 등 값 제약은 **추가하지 않는다**.
- **Rationale**: NOT NULL 은 "값 없음 = 빈 배열" 표현을 하나로 고정해 앱의 null 분기를 없앤다(기존 `name_translations NOT NULL` 과 동일 원칙). 값 내용 검증(확률 범위·중복 코드)은 사용자 지시대로 DB 가 아니라 애플리케이션 책임 — 구 테이블의 `ck_fas_inclusion_percent`·unique 제약을 JSON 에 재현하지 않는다.
- **Alternatives considered**: NULL 허용 + 앱에서 coalesce — Kotlin non-null 타입과 어긋나 방어 코드 필요, 기각. JSON CHECK 제약 — 사용자 지시 위반, 기각.

## R4. 백필 SQL

- **Decision**: 단일 UPDATE 로 백필한다:
  ```sql
  UPDATE food f
  SET f.avoidance_substances = COALESCE(
    (SELECT JSON_ARRAYAGG(JSON_OBJECT('code', s.substance_code, 'inclusion_percent', s.inclusion_percent))
     FROM food_avoidance_substance s
     WHERE s.food_id = f.id AND s.status = 'ACTIVE'),
    JSON_ARRAY());
  ```
  원본 테이블은 읽기만 하고 수정·삭제하지 않는다.
- **Rationale**: `status='ACTIVE'` 필터는 소프트삭제 규약(`@SQLRestriction`)과 동일한 가시성 — 기존 `@OneToMany` 조회가 보던 집합과 정확히 일치한다. `JSON_ARRAYAGG` 는 MySQL 8 기본 제공.
- **Alternatives considered**: 애플리케이션 코드(배치성 스크립트)로 백필 — Flyway 단일 마이그레이션으로 충분한 규모, 기각.

## R5. 도메인 메서드·소비처 전환 범위

- **Decision**: `Food.avoidanceSubstancesByProbability()`·`overallRisk(Set<String>)` 의 시그니처(이름·파라미터)는 유지하고 원소 타입만 `FoodAvoidanceItem` 으로 바꾼다. `riskLevel()` 은 `FoodAvoidanceItem` 으로 이동. 소비처는 `FoodService.getDetail` 의 필드 참조(`substanceCode`→`code`)만 수정하면 되고, `ScanService`·`FoodSummaryView`·컨트롤러 DTO 는 무수정.
- **Rationale**: 정렬은 이미 애플리케이션(`sortedByDescending`)이 수행 중 — 사용자 지시와 현행 구조가 일치하므로 로직 이동이 불필요하다. 시그니처 유지로 파급을 food 모듈 안에 가둔다.
- **Alternatives considered**: 정렬을 SQL(JSON_TABLE)로 — 사용자 지시(DB 는 저장만) 위반, 기각.

## R6. 보존 대상과 신규 쓰기 경로의 부재

- **Decision**: `FoodAvoidanceSubstance` 엔티티·`FoodAvoidanceSubstanceJpaRepository`·`food_avoidance_substance` 테이블·배치(`AvoidanceScoringJob`·`FoodScoringSource`)는 **모두 무수정 보존**한다. 이번 작업에서 JSON 컬럼에 대한 신규 쓰기 API 는 만들지 않는다(쓰기는 백필 1회뿐 — 배치 전환은 후속).
- **Rationale**: 사용자 지시(테이블 삭제 금지·배치 무시). main 소스에서 리포지토리는 이미 미사용이고 배치는 읽기 전용 창구(`FoodScoringSource`)만 물고 있어 충돌 지점이 없다.
- **주의**: `FoodService.upsertIncomplete` 의 네이티브 INSERT 는 food 테이블 컬럼을 직접 나열하므로 NOT NULL 인 새 컬럼(`'[]'`)을 추가해야 한다. api 통합 테스트 시드들(`FoodTestSeed`·`HomeTestSeed`·`ScenarioFoodSeed` 등)의 food INSERT 도 동일.
