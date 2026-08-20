# Data Model: KB-369

## 스키마 변경

**없음.** Flyway 마이그레이션 없음 — 기존 `scan_history` 읽기 전용 활용.

## 판정에 쓰는 기존 구조

```
scan_history
├── member_id  bigint NOT NULL   ← 판정 키 1
├── food_id    bigint NULL       ← 판정 키 2 (매칭 실패 스캔이면 NULL → 자격 불인정)
└── status     'ACTIVE'|'DELETED' (@SQLRestriction 로 ACTIVE 만 조회)
```

- 자격 = `exists(scan_history where member_id = :me and food_id = :food)` (ACTIVE 자동).
- 인덱스: `idx_scan_history_recent(member_id, created_at)` member_id 프리픽스 사용. 신규 인덱스 없음.

## 응답 모델 변경

- `GetFoodDetailResult` / `FoodDetailResponse`: `reviewEligible: Boolean?` 추가 — memberId null(비회원)이면 null.

## 에러 코드

- `REVIEW_NOT_ELIGIBLE("REVIEW-004", 403, "스캔 이력이 있는 음식에만 리뷰를 작성할 수 있습니다")` 신설.
