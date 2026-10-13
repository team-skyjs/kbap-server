# Data Model: 홈 인기 음식 마케팅 임시 목록

DB 스키마 변경 없음. 신규 엔티티·마이그레이션 없음.

## 설정 모델 — `HomeMarketingPopularProperties` (`com.kbap.api.home`)

| 필드 | 타입 | 설정 키 | env | 기본 | 의미 |
|------|------|---------|-----|------|------|
| `startsAt` | `LocalDateTime?` | `kbap.home.marketing-popular.starts-at` | `HOME_MARKETING_POPULAR_STARTS_AT` | null | 적용 시작(포함), Asia/Seoul 로컬 시각, ISO `2026-11-01T00:00:00` |
| `endsAt` | `LocalDateTime?` | `kbap.home.marketing-popular.ends-at` | `HOME_MARKETING_POPULAR_ENDS_AT` | null | 적용 종료(제외) |
| `foodIds` | `List<Long>` | `kbap.home.marketing-popular.food-ids` | `HOME_MARKETING_POPULAR_FOOD_IDS` | 빈 목록 | 노출 순서의 음식 id, 콤마 구분 `6,4,5` |

### 판정 규칙 — `activeFoodIds(now): List<Long>`

```
비활성(빈 목록 반환):  startsAt == null  ∨  endsAt == null  ∨  startsAt ≥ endsAt
                    ∨  now < startsAt  ∨  now ≥ endsAt  ∨  foodIds 비어 있음
활성:               foodIds.distinct()  (첫 등장 순서 유지)
```

- 형식 오류(숫자 아닌 id, 파싱 불가 시각)는 바인딩 실패 → 앱 기동 실패(배포 전 노출).
- 빈 문자열 env 는 null / 빈 목록으로 바인딩된다(단위 테스트로 고정).

## 홈 조립 입력 — `HomeService`

| 경로 | 인기 id 출처 | READY 필터 | 상한 |
|------|-------------|-----------|------|
| `getHome` (1.0) | `PopularFoodIdCache.getPopularFoodIds(10)` | SQL(`findPopular`)이 이미 적용 | 10 |
| `getMarketingHome` (2.1+) 활성 | `activeFoodIds(now).take(10)` | `Food.isReady()` 후필터 | ≤10 |
| `getMarketingHome` 비활성 | `getHome` 위임 | — | — |

## 기존 엔티티 — `Food` (변경 없음)

- 노출 가능 조건: `status = ACTIVE`(`@SQLRestriction` 자동) ∧ `contentStatus = READY`(`isReady()`).
- 조회: `FoodJpaRepository.findByIdIn(ids)` → `loadInGivenOrder` 가 입력 순서로 재배열. 미존재·삭제 id 는 결과에 없어 자연 제외.

## 시각

- `HomeService` 가 `LocalDateTime.now(ZoneId.of("Asia/Seoul"))` 를 넘긴다. Clock 빈 없음.
