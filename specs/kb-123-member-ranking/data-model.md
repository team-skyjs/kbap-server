# Phase 1 Data Model: 회원 랭킹 산정 및 조회

**`member` 테이블에 컬럼 1개 추가**(`scan_count`) + Flyway 마이그레이션 1건. 신규 테이블 없음. 점수·등급은 저장하지 않고 카운트에서 조회 시점에 계산한다. 랭킹은 `Member` 애그리거트의 하위 개념이다.

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

`scanCount: Int` 를 갖는다 — 가입 시 0, `recordScan()` 이 1 올린 새 인스턴스를 반환하고, `ranking()` 이 자기 카운트로 `MemberRanking` 을 만든다(리뷰·다양성은 리뷰 도메인 부재로 0).

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

### member (기존 테이블, 컬럼 1개 추가)

| 컬럼 | 타입 | 비고 |
|------|------|------|
| scan_count | INT NOT NULL DEFAULT 0 | 메뉴판 스캔 횟수. **가입 시 0**(`Member.signUp` + DEFAULT) |

- **스캔 1회 = 메뉴판 1장**(정책 확정). 매칭된 음식 수와 무관하게 `ScanUseCase.assessMenuBoard` 호출당 1 증가하며, 매칭 결과가 하나도 없어도 오른다.
- 카운트업은 `Member.recordScan()`(불변 — 새 인스턴스 반환) 후 `MemberRepository.update`. 회원 로드 → 증가 → 저장이라 **같은 회원의 동시 스캔에서 1회가 유실될 수 있다** — 초기 단계라 감수하며, 문제가 되면 이벤트 기반 집계로 전환한다.
- 탈퇴하면 회원 행과 함께 소프트 삭제되므로 따로 정리할 카운터가 없다.
- 리뷰 수·고유 음식 수는 리뷰 도메인 도입 시 같은 방식으로 `member` 에 컬럼을 추가한다(현재 계산에서 0).

### scan_history (변경 없음)

음식 단위 이력이라 **스캔 횟수 집계에는 쓰지 않는다**(메뉴판 1장에서 음식 N개가 매칭되면 N행). 홈 화면의 "최근 스캔" 용도 그대로 둔다.

### 리뷰 (미존재)

리뷰 테이블·도메인이 아직 없다. 리뷰 수·고유 음식 수는 0으로 산정한다.

## 애그리거트

랭킹 전용 리포지토리·포트는 없다. `MemberRepository`(기존 port)로 회원을 읽고 저장하며, 랭킹은 `Member` 가 자기 카운트에서 파생한다(`Member.ranking()`).
