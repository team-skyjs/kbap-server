# Data Model: 음식 candidate 스테이징 파이프라인 (KB-96)

**Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

## 엔티티 개요

| 엔티티 | 계층/모듈 | 역할 |
|--------|-----------|------|
| `FoodCandidate` | 도메인 `:core:research` (ORM-free) | 제작 중 음식의 스테이징 표현 + 완성 판정 |
| `SubstanceSnapshot` | 도메인 `:core:research` | 성분 매핑 스냅샷 값(코드+확률) |
| `FoodCandidateJpaEntity` | 영속 `:infra:persistence` (`BaseEntity` 상속) | `food_candidate` 테이블 매핑 |
| `Food`(기존) | 도메인 `:core:food` | 승격 대상(완성 서빙 음식) — 변경: `FoodRepository.save` 추가 |
| `FoodAvoidanceSubstance`(기존) | 도메인 `:core:food` | 승격 시 스냅샷→정규화 대상 |

## 도메인: `FoodCandidate` (ORM-free, 불변)

```
FoodCandidate
  id: Long?                                   # 미영속 시 null
  koreanName: String                          # 자연키, 필수·blank 불가
  koreanDescription: String?                  # ko 원문(번역 입력 전제). null 가능(미저작)
  descriptionTranslations: Map<LanguageCode, String>   # 9개 대상 언어(KO 제외)
  substanceMapping: List<SubstanceSnapshot>   # 성분 코드+확률 스냅샷
  publishedFoodId: Long?                       # 승격 링크(멱등). null = 미승격

  fun isComplete(): Boolean =
      koreanDescription != null &&
      substanceMapping.isNotEmpty() &&
      descriptionTranslations.keys == TARGET_LANGUAGES &&   # 9개 정확 일치(초과·누락·비지원 배제)
      publishedFoodId == null
```

- **TARGET_LANGUAGES** = `LanguageCode.entries - KO` (9개, 원칙 V). 번역 완비 = 키 집합이 정확히 이 9개.
- **불변**: 모든 필드 `val`, 상태 변경은 새 인스턴스 반환(도메인 불변 규약). 단, 스테이징 부분 갱신은 영속 레벨의 컬럼-스코프 UPDATE 로 처리하므로 도메인에서 잦은 copy 는 지양(조회→판정 위주).
- **검증(init)**: `koreanName.isNotBlank()`; 번역 맵 키는 지원 언어(KO 제외)만; 확률은 `SubstanceSnapshot` 에서 1..100.

### `SubstanceSnapshot`

```
SubstanceSnapshot
  code: String            # AvoidanceSubstanceCode 이름(코드 참조, enum 직접 의존 X)
  inclusionPercent: Int   # 1..100
```

## 영속: `food_candidate` 테이블 (Flyway, owner=:app:api)

```sql
CREATE TABLE food_candidate (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name              VARCHAR(255) NOT NULL,
    description              VARCHAR(255) NULL,               -- ko 원문(미저작 시 null)
    description_translations JSON         NOT NULL,           -- {lang: text} 9개 대상 언어, 기본 {}
    substance_mapping        JSON         NOT NULL,           -- [{code, percent}], 기본 []
    published_food_id        BIGINT       NULL,               -- 승격 링크(멱등)
    status                   VARCHAR(20)  NOT NULL,           -- EntityStatus ACTIVE/DELETED (BaseEntity)
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_food_candidate_korean_name (korean_name),
    KEY idx_food_candidate_promotable (published_food_id)     -- 미승격 후보 조회 가속
);
```

- **길이·타입 MySQL 기준**(H2 미고려). `description` 255(= `FoodContent.MAX_DESCRIPTION_LENGTH` 정합).
- **소프트삭제**: `BaseEntity` 의 `@SQLRestriction("status='ACTIVE'")` 상속 → 조회 자동 ACTIVE.
- **JSON 컬럼**: `@JdbcTypeCode(SqlTypes.JSON)` (기존 `FoodJpaEntity` 번역 컬럼과 동형).
- Flyway 버전은 **점 구분 timestamp**(`V2026.07.07.HH.mm.ss__create_food_candidate.sql`). 이 골격 단계라 로컬 MySQL 부팅으로 검증(테스트는 Testcontainers 부트스트랩).

### `FoodCandidateJpaEntity` (매핑·변환)

- `korean_name`, `description`(nullable), `descriptionTranslations: Map<String,String>`(JSON), `substanceMapping: List<...>`(JSON), `publishedFoodId: Long?`.
- `fun toDomain(): FoodCandidate` / `companion object { fun from(domain): FoodCandidateJpaEntity }` — 변환은 엔티티 안(도메인은 JPA import 안 함).
- JSON ↔ 도메인 변환 시 언어 키(String)↔`LanguageCode` 해석은 기존 `resolve(...)` 패턴 재사용.

## 상태 전이 (유도, 명시 컬럼 없음)

```
[생성]  korean_name(+ko desc) 있음, 번역·성분 빈 값, published_food_id=null
   │  (KB-54) substance_mapping 채움  ∥  (KB-94) description_translations 채움   ← 컬럼-스코프, 순서 무관
   ▼
[완성]  isComplete()==true  (성분≥1 && ko desc && 번역 9개 && 미승격)
   │  승격 배치: food 업서트 + food_avoidance_substance 재적재
   ▼
[승격]  published_food_id = <food.id>   (다음 조회에서 제외 — 멱등)
```

- 미완성은 스테이징에 잔류(다음 실행 재평가). 실패는 `published_food_id` 미설정으로 재시도 대상.

## 승격 대상(기존 테이블, 변경 없음)

- **`food`**: `korean_name`(UNIQUE) 기준 업서트. `description`·`name_translations`·`description_translations`·`spiciness` 채움. (spiciness 는 candidate 범위 밖 — 기본/기존값 정책은 승격 매핑에서 결정, 초기엔 0.)
- **`food_avoidance_substance`**: candidate `substance_mapping` 스냅샷을 `(food_id, substance_code, inclusion_percent)` 로 정규화 재적재.

## 검증 규칙 요약 (FR 매핑)

| 규칙 | 출처 |
|------|------|
| korean_name 필수·UNIQUE | FR-001, FR-005 |
| 완성 = 성분≥1 && ko desc && 번역 9개 && 미승격 | FR-003 |
| 컬럼-스코프 부분 업데이트(타 잡 컬럼 보존) | FR-002, SC-004 |
| 완성분만 food 적재 | FR-004, SC-001/002 |
| korean_name 업서트 + published 링크 멱등 | FR-005, SC-003 |
| 음식 1건=트랜잭션 1개(부분 실패 격리) | FR-006, SC-005 |
| 성분 코드 참조(enum 직접 의존 X) | 원칙 II |
