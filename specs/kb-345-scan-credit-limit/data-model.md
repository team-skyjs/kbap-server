# Data Model: 회원 스캔 무료 3회·리뷰 해금

## Member (`member`) — 컬럼 1개 추가

| 필드 (Kotlin) | 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|---|
| `scanUnlocked` | `scan_unlocked` | `BOOLEAN(tinyint(1))` | `NOT NULL DEFAULT 0` | 스캔 무제한 해금 여부 — 리뷰 작성 시 true, 재잠금은 후속 배치가 false 로 회수 |

- 백필 없음(확정) — 기존 회원 전원 false 시작.
- 판정식: **허용 = `scanUnlocked || scanCount < 3`** (`scanCount` 는 기존 랭킹 카운터 재사용 — 성공 스캔 누적과 의미 일치).

## Flyway

```sql
ALTER TABLE member
    ADD COLUMN scan_unlocked tinyint(1) NOT NULL DEFAULT 0;
```

- 파일명 `V<생성시각 timestamp>__member_scan_unlocked.sql`. additive DEFAULT 라 블루/그린 안전·순서 독립.

## 쿼리 변경

- `MemberJpaRepository.increaseReviewCount`: `set m.reviewCount = m.reviewCount + 1, m.scanUnlocked = true` — 리뷰 작성 = 즉시·원자 해금.
- 신규 쿼리 없음 — 판정은 `ScanService` 가 이미 로드하는 `member` 로 수행.

## ErrorCode

- `SCAN_LIMIT_EXCEEDED("SCAN-004", 403, "무료 스캔 횟수를 모두 사용했습니다. 리뷰를 작성하면 무제한으로 이용할 수 있어요")`
