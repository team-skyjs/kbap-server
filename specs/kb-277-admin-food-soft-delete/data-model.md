# Data Model: 관리자 음식 삭제(소프트)

## 스키마 변경

**없음.** Flyway 마이그레이션을 추가하지 않는다.

- `food.status` (BaseEntity 공통 컬럼, `EntityStatus` ACTIVE/DELETED) 를 그대로 사용한다.
- `uq_food_korean_name` unique 제약은 status 를 포함하지 않으므로 **삭제된 음식도 이름을 계속 점유**한다 — 의도된 기존 구조(재시드 누락 동작의 원인이자 FR-007 안내 대상).
- FK(`fk_food_review_*` 등)는 ON DELETE 없는 소프트 삭제 전제 — row 가 남으므로 위반 없음.

## 엔티티

### Food (`com.kbap.common.domain.food.model.Food`) — 변경 없음

| 관련 필드 | 타입 | 이번 기능에서의 역할 |
|-----------|------|---------------------|
| `status` (BaseEntity) | `EntityStatus` | ACTIVE → DELETED 전이가 삭제의 전부. `@SQLRestriction("status = 'ACTIVE'")` 로 전 조회에서 자동 제외 |
| `koreanName` | `String` | 삭제 후에도 unique 점유 유지 — 삭제 UI 안내 문구의 근거 |

**상태 전이**: `food.delete()` (BaseEntity 제공, dirty checking) — ACTIVE → DELETED, 단방향(복구 UI 없음, 스펙 Assumption).

## API 계층 신규 타입 (`com.kbap.api.admin`)

```
enum AdminFoodDeleteResult { DELETED, NOT_FOUND }
```

- `AdminFoodService.deleteFood(id: Long): AdminFoodDeleteResult`
  - `@Transactional`, `findById` null → NOT_FOUND (미존재·기삭제 합류 — research R3)
  - 존재 시 `food.delete()` → DELETED

## 참조 데이터 영향

| 데이터 | 삭제 시 처리 | 노출 |
|--------|-------------|------|
| food_review | 보존 (변경 없음) | 음식 진입점이 사라져 리뷰 목록 접근 자체가 FOOD not found |
| bookmark | 보존 (변경 없음) | 북마크 목록에서 해당 항목 drop (`mapNotNull` 기존 동작) |
| scan 이력 | 보존 (변경 없음) | 기존 동작 유지 |
