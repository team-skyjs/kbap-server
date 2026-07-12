# Phase 1 Data Model: 회원 랭킹 산정 및 조회

**신규 테이블 1개**(`member_ranking`) + Flyway 마이그레이션 1건. 점수·등급은 저장하지 않고 카운트에서 조회 시점에 계산한다.

## 도메인 모델 (`:core:member`, ORM-free · Spring-free)

### RankingTier (enum)

7단계 등급 사다리. 안정 키(enum 이름의 소문자 표현)·레벨·진입 점수를 갖는다. **값은 계약** — FE 번역이 키에 의존하므로 이름을 바꾸지 않는다.

| 상수 | 안정 키 | level | 진입 점수(minScore) |
|------|---------|-------|--------------------|
| NEWCOMER | `newcomer` | 1 | 0 |
| TASTER | `taster` | 2 | 30 |
| EXPLORER | `explorer` | 3 | 80 |
| REGULAR | `regular` | 4 | 180 |
| GOURMET | `gourmet` | 5 | 350 |
| KFOOD_MASTER | `kfood_master` | 6 | 600 |
| KOREAN_AT_HEART | `korean_at_heart` | 7 | 1000 |

규칙:
- `of(score)`: 진입 점수가 `score` 이하인 등급 중 가장 높은 것. 경계값은 상위 등급으로 판정한다(30 → TASTER).
- `next`: 다음 등급(최고 등급이면 `null`).

### MemberRanking (값 객체, 불변)

조회 시점에 산출되는 회원 1인의 랭킹. 저장하지 않는다.

| 필드 | 타입 | 규칙 |
|------|------|------|
| reviewCount | Int | 작성한 리뷰 개수. **현재 항상 0**(리뷰 도메인 부재) |
| uniqueReviewedFoodCount | Int | 리뷰한 서로 다른 음식 수. **현재 항상 0** |
| scanCount | Int | 메뉴판 스캔 횟수 |
| score | Int | `reviewCount × 10 + uniqueReviewedFoodCount × 5 + scanCount × 2` (파생) |
| tier | RankingTier | `RankingTier.of(score)` (파생) |
| nextTier | RankingTier? | `tier.next` — 최고 등급이면 null (파생) |
| pointsToNext | Int? | `nextTier.minScore − score` — 최고 등급이면 null (파생) |

- 생성: `MemberRanking.of(reviewCount, uniqueReviewedFoodCount, scanCount)`. 카운트는 음수일 수 없다.
- 점수 내역(breakdown)은 별도 저장 필드가 아니라 세 카운트와 배점(10·5·2)에서 계산한다 — `reviews(count, points)` · `diversity(count, points)` · `scans(count, points)`. 세 points 의 합은 항상 `score` 와 같다.

## 카운트 소스

### member_ranking (신규 테이블)

회원의 활동 카운터를 **회원당 1행**으로 누적한다. 점수·등급은 담지 않는다(계산은 조회 시점).

| 컬럼 | 타입 | 비고 |
|------|------|------|
| id | BIGINT PK | BaseEntity |
| member_id | BIGINT NOT NULL | **UNIQUE** (`uk_member_ranking_member`), FK → member(id) |
| scan_count | INT NOT NULL DEFAULT 0 | 메뉴판 스캔 횟수 |
| status | VARCHAR(20) | BaseEntity — 소프트삭제 |
| created_at / updated_at | DATETIME(6) | BaseEntity |

- **스캔 1회 = 메뉴판 1장**(정책 확정). 매칭된 음식 수와 무관하게 `ScanUseCase.assessMenuBoard` 호출당 1 증가하며, 매칭 결과가 하나도 없어도 오른다.
- 카운트업은 `INSERT ... ON DUPLICATE KEY UPDATE scan_count = scan_count + 1` 로 원자적이다(행이 없으면 생성). 유니크 키가 이 upsert 의 전제라 필수다.
- 기록이 없는 회원의 조회는 0으로 취급한다(행을 미리 만들지 않는다).
- 리뷰 수·고유 음식 수는 리뷰 도메인 도입 시 이 테이블에 컬럼으로 추가한다.

### scan_history (변경 없음)

음식 단위 이력이라 **스캔 횟수 집계에는 쓰지 않는다**(메뉴판 1장에서 음식 N개가 매칭되면 N행). 홈 화면의 "최근 스캔" 용도 그대로 둔다.

### 리뷰 (미존재)

리뷰 테이블·도메인이 아직 없다. 리뷰 수·고유 음식 수는 0으로 산정한다.

## 포트 (`:core:member`)

```
interface MemberRankingRepository {
    fun increaseScanCount(memberId: Long)
    fun scanCountOf(memberId: Long): Int
}
```

구현은 `:infra:persistence` 의 `MemberRankingRepositoryAdapter`. `ScanUseCase` 는 카운트업만, `MemberRankingUseCase` 는 조회만 쓴다.
