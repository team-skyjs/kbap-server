# Phase 1 Data Model: 메뉴 스캔 메뉴명 정제

스캔은 아무것도 저장하지 않는다. 유일한 영속 변경은 **`food`에 콘텐츠 완성 상태·매칭 키를 더하는 것**이다.

## 값타입

### InterpretedName (`:core:kernel`, sealed) — LLM 정제 결과

| 변형 | 필드 | 의미 |
|------|------|------|
| `StandardName` | `korean: String`(blank 불가) | 표준 한국어 메뉴명 |
| `NotFood` | — | 음식 아님 → 응답 결과에서 제외 |

### MenuItemMatch (`:core:scan`, sealed) — 항목 매칭 결과

| 변형 | 필드 | 진입 경로 | 응답 `matchStatus` |
|------|------|-----------|--------------------|
| `Matched` | `foodId: Long`(양수) | 완성(READY) 음식과 매칭 | `MATCHED` |
| `Unmatched` | `foodId: Long?` | 미완성 음식 매칭/신규 등록(id 있음), 또는 폴백 판정불가(null) | `UNMATCHED` |

메뉴가 아닌 항목은 `MenuItemMatch` 자체를 만들지 않는다(내부적으로 `Resolution = null` → 결과에서 제외).

### FoodContentStatus (`:core:food`)

| 값 | 의미 |
|----|------|
| `INCOMPLETE` | 스캔으로 발견됐으나 레시피·설명·번역 미충족. 일반 조회 미노출, 위험도 항상 `UNKNOWN` |
| `READY` | 콘텐츠 완성. 조회·위험도 산출 대상 |

## 엔티티

### Food (`:core:food`, 확장)

기존: `id, content(name·description·번역), imageRef, spiciness, avoidanceSubstances`. **추가**: `contentStatus: FoodContentStatus`.

- `Food.incomplete(koreanName)` — 한국어명만 가진 미완성 음식 팩토리. description은 플레이스홀더(`LocalizedText.korean`이 blank 불가), spiciness 0, 성분 없음.
- `Food.isReady()` — 완성 여부.
- `Food.overallRisk(avoidedCodes)` — **`!isReady()`면 무조건 `UNKNOWN`**. 성분이 비었다고 `SAFE`가 되는 것을 도메인에서 차단.
- `reconstitute(..., contentStatus = READY)` — 기본값 READY(기존 호출부 호환).

### 스캔 관련 엔티티 — **없음**

`MenuScan`·`ScannedMenuItem`·`ScanStatus`·`BoundingBox`·`MenuItemAssessment`·`MenuScanRepository`는 제거됐다. `:core:scan`에는 `MenuItemMatch`만 남는다.

## 영속 (`:infra:persistence`)

### food (컬럼 추가)

```sql
-- 콘텐츠 완성 상태 (기존 row 는 완성본이므로 READY 로 백필)
ALTER TABLE food ADD COLUMN content_status VARCHAR(20) NOT NULL DEFAULT 'READY';
CREATE INDEX idx_food_content_status ON food (content_status);

-- 정규화 매칭 키(생성 저장 컬럼) — COLLATE utf8mb4_bin 필수(아래 주의)
ALTER TABLE food ADD COLUMN korean_match_key VARCHAR(255)
  GENERATED ALWAYS AS (REGEXP_REPLACE(korean_name COLLATE utf8mb4_bin, '[^가-힣]', '')) STORED;
CREATE INDEX idx_food_korean_match_key ON food (korean_match_key);
```

- ⚠️ **`COLLATE utf8mb4_bin`을 빼면 MySQL 기본 collation에서 `[^가-힣]` 범위가 정렬 순서로 해석돼 마이그레이션이 실패한다.** Testcontainers는 collation이 달라 못 잡으므로 로컬 MySQL 검증 필수.
- `FoodJpaEntity`는 `korean_match_key`를 **읽기 전용 매핑**(`insertable=false, updatable=false`). `@Generated`는 소프트삭제(`@SQLRestriction`)와 충돌해 쓰지 않는다.
- `korean_name`엔 기존 UNIQUE 제약(`uq_food_korean_name`)이 있다 → `createIncomplete`는 upsert 로 get-or-create.
- ⚠️ 이 UNIQUE 를 **엔티티에도 선언**한다(`@Table(uniqueConstraints=…)`). 선언이 없으면 `:infra:persistence` 테스트가 Hibernate 생성 스키마(제약 없음)에서 돌아 **경합·중복 결함을 영영 못 잡는다**(실제로 그랬다).

### 상태 컬럼은 MySQL ENUM

`status`(5개 테이블)·`content_status`·`onboarding_status`·`provider` 를 `VARCHAR(20)` → `ENUM(...)` 으로 고정했다. VARCHAR 이면 오타(`ACTIV`)가 그대로 적재되고, 이후 `@SQLRestriction("status = 'ACTIVE'")` 에 안 걸려 **행이 조용히 사라진 것처럼** 보인다.

- 오타는 `STRICT_TRANS_TABLES` 에서 `ERROR 1265` 로 거부된다. **strict 가 꺼지면 빈 문자열로 조용히 들어간다** — 이 보호는 sql_mode 에 의존한다.
- Hibernate 6 는 `@Enumerated(EnumType.STRING)` 컬럼에 CHECK 제약을 자동 생성하지만, **Flyway 로 만든 프로덕션 스키마엔 그게 없다.** 그래서 마이그레이션이 필요했다. 반대로 `content_status` 는 엔티티가 `String` 매핑이라 생성 스키마에도 보호가 없었다.
- 엔티티에 `columnDefinition = "ENUM(...)"` 을 달아 **테스트 생성 스키마와 프로덕션을 일치**시킨다(UNIQUE 때와 같은 함정 방지).
- 값 추가는 목록 **끝에 append** 하면 MySQL 8 에서 `ALGORITHM=INSTANT`(메타데이터만). 중간 삽입·순서 변경은 테이블 재작성이므로 하지 않는다.

### 스캔 테이블 — **DROP**

```sql
DROP TABLE IF EXISTS scanned_menu_item;  -- 자식(FK) 먼저
DROP TABLE IF EXISTS menu_scan;
```
`create_scan_tables.sql`은 develop에 이미 적용돼 파일 삭제가 금지되므로(CLAUDE.md) DROP 마이그레이션으로 되돌린다.

## 쿼리와 serving gate

| 메서드 | 대상 | 완성 상태 필터 |
|--------|------|----------------|
| `findFoodPage(cursor, size)` | 메뉴 목록(사용자) | **JPQL에 `contentStatus = 'READY'`** — 페이지 크기 정확성 때문에 쿼리 레벨 |
| `searchFoodPage(kw, lang, …)` | 메뉴 검색(사용자) | **네이티브 쿼리에 `content_status = 'READY'`** — `@SQLRestriction`이 안 걸리므로 `status='ACTIVE'`와 함께 명시 |
| `findById(id)` | 음식 상세(사용자) | **어댑터에서 `takeIf { it.isReady() }`** |
| `findByKoreanMatchKeys(keys)` | 스캔 매칭 | **필터 없음** — 미완성 음식도 매칭돼야 재등록을 막는다 |
| `findByIdInWithAvoidanceSubstances(ids)` | 상세 + **스코어링 배치 공유** | **필터 없음** — 배치가 미완성 음식을 봐야 채울 수 있다 |
| `createIncomplete(koreanNames)` | 스캔 miss | 스캔당 1회·문장 2개. 다중행 upsert(`ON DUPLICATE KEY UPDATE id=id`) + `IN` fetch join 재조회 |

## Flyway 파일 (점 구분 timestamp)

- `V…__add_foods_korean_match_key.sql` — 생성 컬럼 + 인덱스
- `V…__add_food_content_status.sql` — 완성 상태 + 인덱스(READY 백필)
- `V…__drop_menu_scan_tables.sql` — 스캔 테이블 제거

각 마이그레이션은 순서 비의존(out-of-order 전제).
