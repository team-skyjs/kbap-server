# Data Model: 음식 기피성분 JSON 컬럼 이관

## food 테이블 (변경)

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `avoidance_substances` | JSON | NOT NULL (값 제약 없음) | 기피성분 목록 `[{"code": "SOY", "inclusion_percent": 34}, …]`. 빈 목록은 `[]`. 정렬·유효성은 애플리케이션 책임 |

기존 컬럼·인덱스 변경 없음. CHECK·UNIQUE·FK 등 값 수준 제약은 추가하지 않는다(사용자 지시 — DB 는 저장만).

## food_avoidance_substance 테이블 (보존 — 무변경)

백필 원본. 테이블·데이터·제약 모두 그대로 유지. 배치가 계속 이 테이블 경로를 사용한다(전환은 후속 작업).

## 엔티티 / 값 객체

### FoodAvoidanceItem (신규 — `com.kbap.domain.food.model`)

```kotlin
data class FoodAvoidanceItem(
    val code: String,                                        // 성분 코드 문자열 (avoidance enum 미참조 — 원칙 II)
    @JsonProperty("inclusion_percent") val inclusionPercent: Int,
) {
    fun riskLevel(): RiskLevel = RiskLevel.fromInclusionProbability(inclusionPercent)
}
```

- JSON 직렬화 키: `code`, `inclusion_percent` (Jira KB-210 명세 고정 — 백필 SQL 의 `JSON_OBJECT` 키와 일치).

### Food (수정)

- 제거: `@OneToMany(EAGER)+@JoinColumn+@BatchSize` 연관 `avoidanceSubstances: MutableList<FoodAvoidanceSubstance>` — 유일했던 JPA 연관관계 예외 소멸(원칙 IV 완전 준수).
- 추가: `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "avoidance_substances", nullable = false) var avoidanceSubstances: List<FoodAvoidanceItem> = emptyList()` — 기존 `nameTranslations` 매핑 패턴과 동일.
- 도메인 메서드(시그니처 유지, 원소 타입만 교체):
  - `avoidanceSubstancesByProbability(): List<FoodAvoidanceItem>` — 확률 내림차순 정렬(애플리케이션 정렬).
  - `overallRisk(avoidedCodes: Set<String>): RiskLevel` — `it.code in avoidedCodes` 필터로 변경.

### FoodAvoidanceSubstance / FoodAvoidanceSubstanceJpaRepository (보존 — 무수정)

구 엔티티·리포지토리는 배치 전환(후속)까지 그대로 둔다. main 소스 소비자는 현재 0곳.

## 마이그레이션 (Flyway — `:app:api`, timestamp 버전)

`V2026.07.21.HH.mm.ss__add_food_avoidance_substances_json.sql` (파일 생성 시각으로 채번):

```sql
ALTER TABLE food ADD COLUMN avoidance_substances JSON NULL;

UPDATE food f
SET f.avoidance_substances = COALESCE(
  (SELECT JSON_ARRAYAGG(JSON_OBJECT('code', s.substance_code, 'inclusion_percent', s.inclusion_percent))
   FROM food_avoidance_substance s
   WHERE s.food_id = f.id AND s.status = 'ACTIVE'),
  JSON_ARRAY());

ALTER TABLE food MODIFY COLUMN avoidance_substances JSON NOT NULL;
```

- 3단계인 이유: MySQL 은 JSON 컬럼에 DEFAULT 를 줄 수 없어 non-empty 테이블에 NOT NULL 로 바로 추가 불가.
- `status='ACTIVE'` 필터: 기존 `@SQLRestriction` 가시성과 동일 집합 백필.
- 독립 실행 가능(out-of-order 안전): `food`·`food_avoidance_substance` 는 init 스키마가 보장.

## 상태 전이

없음 — 콘텐츠 상태(`content_status`)·소프트삭제 규약 변화 없음.

## 파급 소비처 (main)

| 위치 | 변경 |
|------|------|
| `FoodService.getDetail` | `substance.substanceCode` → `item.code` (정렬·필터 로직 불변) |
| `FoodService.upsertIncomplete` | 네이티브 INSERT 컬럼에 `avoidance_substances`(`'[]'`) 추가 |
| `ScanService` · `FoodSummaryView` · web DTO | 무수정 (`overallRisk` 시그니처 유지) |
