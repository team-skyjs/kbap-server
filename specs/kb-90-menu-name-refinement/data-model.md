# Phase 1 Data Model: 메뉴 스캔 메뉴명 정제

기존 스캔·food 모델에 매칭 상태와 대기열을 더한다. 도메인은 ORM-free, 영속은 `:infra:persistence`.

## 값타입 (kernel / scan 도메인)

### InterpretedName (`:core:kernel`, sealed) — P1

LLM 해석 결과.

| 변형 | 필드 | 의미 |
|------|------|------|
| `StandardName` | `korean: String`(blank 불가) | 표준 한국어 메뉴명 |
| `NotFood` | — | 음식 아님 |

### MenuItemMatch (`:core:scan`, sealed 또는 status+foodId) — P1

스캔 항목의 정제·매칭 결과. `ScannedMenuItem` 이 보유. **항상 아래 3종 중 하나로 종결**(중간상태 없음).

| 상태 | 데이터 | 진입 경로 |
|------|--------|-----------|
| `MATCHED` | `foodId: Long` | 표준명 exact 매치 hit, 또는 폴백 정규화 exact 매치 hit |
| `PENDING` | — | 표준명(또는 폴백 원문)이 미등록 → 대기열 등록 |
| `NOT_FOOD` | — | LLM NOT_FOOD 판정, 또는 정규화 빈 키(한글 0자) pre-filter |

**산출**: 정상 경로 = 정규화 게이트 → LLM 판정 → 매치. 폴백(LLM 실패/미구성) = 정규화 exact 매치. 어느 쪽이든 위 3종으로 종결.

## 엔티티

### ScannedMenuItem (`:core:scan`, 기존 확장) — P1

기존: `id, itemId, rawMenuName, boundingBox, assessment(mock risk)`. **추가**: `match: MenuItemMatch`.

- 불변(헌법·도메인 규약) — 상태 변경은 새 인스턴스 반환.
- `rawMenuName` 은 원문 유지(응답 표시·폴백 대기열 등록에 사용).
- `assessment`(위험도)는 mock 그대로 — 이 작업 범위 밖.

### Food (`:core:food`, 조회만) — P1

변경 없음(도메인). 매칭은 `korean_match_key`(파생) 로 이루어지며 도메인엔 노출 안 함. `Food.koreanName()` 이 키의 원천.

### PendingMenu (`:core:scan`, 신규) — P1

미등록 표준명 대기열 항목.

| 필드 | 타입 | 제약 |
|------|------|------|
| `id` | Long? | IDENTITY |
| `standardName` | String | blank 불가, **unique** |
| `status` | enum `PENDING`/`RESOLVED`/`REJECTED` | 기본 `PENDING` |

## 영속 (`:infra:persistence`)

### foods (컬럼 추가) — P1 · Flyway

```sql
ALTER TABLE foods
  ADD COLUMN korean_match_key VARCHAR(255)
    GENERATED ALWAYS AS (REGEXP_REPLACE(korean_name, '[^가-힣]', '')) STORED;
CREATE INDEX idx_foods_korean_match_key ON foods (korean_match_key);
```

- 생성 저장 컬럼 → 기존/신규 row 자동 계산, 백필 불필요.
- `FoodJpaEntity` 는 이 컬럼을 **읽기 전용 매핑**(`@Column(insertable=false, updatable=false)`) 또는 매핑 생략 후 JPQL/native 조회. `FoodJpaRepository.findByKoreanMatchKey(key)` 로 조회.
- **kernel normalizer ↔ SQL 규칙 동등성 sync 테스트**(Testcontainers) 필수(research D2).

### scanned_menu_item (컬럼 추가) — P1 · Flyway

- `match_status VARCHAR(20) NOT NULL`(MATCHED/PENDING/NOT_FOOD), `matched_food_id BIGINT NULL`.
- `BaseEntity.status`(소프트삭제)와 이름 충돌 피해 `match_status` 사용(scan 의 `scan_status` 선례).

### pending_menus (신규 테이블) — P1 · Flyway

```sql
CREATE TABLE pending_menus (
  id BIGINT NOT NULL AUTO_INCREMENT,
  standard_name VARCHAR(100) NOT NULL,
  queue_status VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,           -- BaseEntity 소프트삭제
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pending_menus_standard_name (standard_name)
);
```

- `BaseEntity` 상속(id/status/created_at/updated_at). 도메인 큐 상태는 `queue_status` 로 분리.
- unique 제약이 dedup 을 강제(SC-005). `enqueue` 는 `INSERT ... ON DUPLICATE KEY UPDATE`(no-op) 또는 존재 조회 후 skip.

## Flyway 파일 (버전 규칙: 점 구분 timestamp)

- P1: `V<생성시각>__add_foods_korean_match_key.sql`, `V<생성시각>__add_scanned_menu_item_match.sql`
- P1: `V<생성시각>__create_pending_menus.sql`

각 마이그레이션은 순서 비의존(out-of-order 전제). 생성 시각 기준 파일명(CLAUDE.md 규칙).
