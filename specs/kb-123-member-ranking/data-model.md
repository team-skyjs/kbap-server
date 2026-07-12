# Phase 1 Data Model: 회원 랭킹 산정 및 조회

**`member` 테이블에 카운트 컬럼 3개 추가**(`scan_count`·`review_count`·`unique_reviewed_food_count`) + Flyway 마이그레이션 1건. 신규 테이블 없음. 점수·등급은 저장하지 않고 카운트에서 조회 시점에 계산한다. 랭킹은 `Member` 애그리거트의 하위 개념이다.

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

### Member (애그리거트 루트, 기존)

`val ranking: Ranking` 을 갖는다 — 가입 시 `Ranking.initial()`(모두 0). 카운트는 **읽기 전용**(점수·등급 파생용)이며, 증가는 영속 계층의 원자적 UPDATE 가 담당한다.

### Ranking (값 객체, 불변 — Member 의 하위 도메인)

카운트 3종을 담고 점수·등급을 스스로 파생한다. 파생값은 저장하지 않는다(컬럼에 저장되는 건 카운트뿐).

| 필드 | 타입 | 규칙 |
|------|------|------|
| scanCount | Int | 메뉴판 스캔 횟수 |
| reviewCount | Int | 작성한 리뷰 개수. **현재 항상 0**(리뷰 도메인 부재) |
| uniqueReviewedFoodCount | Int | 리뷰한 서로 다른 음식 수. **현재 항상 0** |
| score | Int | `reviewCount × 10 + uniqueReviewedFoodCount × 5 + scanCount × 2` (파생) |
| tier | RankingTier | `RankingTier.of(score)` (파생) |
| nextTier | RankingTier? | `tier.next` — 최고 등급이면 null (파생) |
| pointsToNext | Int? | `nextTier.minScore − score` — 최고 등급이면 null (파생) |

- 생성: `Ranking.initial()`(가입) 또는 `Ranking.of(scanCount, reviewCount, uniqueReviewedFoodCount)`(복원). 카운트는 음수일 수 없다.
- 점수 내역(breakdown)은 별도 저장 필드가 아니라 세 카운트와 배점(10·5·2)에서 계산한다 — `reviews(count, points)` · `diversity(count, points)` · `scans(count, points)`. 세 points 의 합은 항상 `score` 와 같다.

## 카운트 소스

### member (기존 테이블, 카운트 컬럼 3개 추가)

랭킹 공식의 원천 카운트 3종. **가입 시 모두 0**(`Member.signUp` + `DEFAULT 0`), 이후 카운트업만 친다.

| 컬럼 | 타입 | 비고 |
|------|------|------|
| scan_count | INT NOT NULL DEFAULT 0 | 메뉴판 스캔 횟수(×2점) — 지금 유일하게 오르는 카운트 |
| review_count | INT NOT NULL DEFAULT 0 | 작성한 리뷰 개수(×10점) — 리뷰 도메인 도입 전이라 0 |
| unique_reviewed_food_count | INT NOT NULL DEFAULT 0 | 리뷰한 서로 다른 음식 수(×5점) — 리뷰 도메인 도입 전이라 0 |

- **스캔 1회 = 메뉴판 1장**(정책 확정). 매칭된 음식 수와 무관하게 `ScanUseCase.assessMenuBoard` 호출당 1 증가하며, 매칭 결과가 하나도 없어도 오른다.
- 카운트업은 **DB 원자적 증가**다 — `MemberRepository.increaseScanCount(memberId)` → `update member set scan_count = scan_count + 1 where id = ? and 활성`. 읽고-더해-쓰는 방식이 아니므로 동시 스캔에서 유실되지 않는다.
- 카운트 컬럼의 소유자는 DB 다 — 프로필 저장(`applyDomain`)은 카운트를 쓰지 않으므로, 오래된 `Member` 스냅샷으로 프로필을 갱신해도 그 사이 오른 카운트를 덮어쓰지 않는다.
- 탈퇴하면 회원 행과 함께 소프트 삭제되므로 따로 정리할 카운터가 없다.
- 리뷰 카운트 2종은 컬럼만 있고 아직 오르지 않는다 — 리뷰 기능이 붙으면 카운트업 호출만 추가하면 되고 마이그레이션·도메인·응답 계약은 그대로다. 고유 음식 수는 "리뷰한 서로 다른 음식" 이라 단순 증가가 아니라 그 음식의 첫 리뷰일 때만 올린다.

### scan_history (변경 없음)

음식 단위 이력이라 **스캔 횟수 집계에는 쓰지 않는다**(메뉴판 1장에서 음식 N개가 매칭되면 N행). 홈 화면의 "최근 스캔" 용도 그대로 둔다.

### 리뷰 (미존재)

리뷰 테이블·도메인이 아직 없어 리뷰 카운트 2종은 0에 머문다. 컬럼은 이미 있으므로 리뷰 기능 도입 시 스키마 변경이 없다.

## 애그리거트

랭킹 전용 리포지토리·애그리거트는 없다. 기존 `MemberRepository` port 로 회원을 읽고(`findById` → `Member.ranking`), 스캔 카운트업만 `increaseScanCount(memberId)` 로 처리한다(원자적 UPDATE, 존재하지 않거나 탈퇴한 회원이면 `MEMBER_NOT_FOUND`).
