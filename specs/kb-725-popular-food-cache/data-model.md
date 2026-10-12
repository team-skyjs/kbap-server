# Data Model: 홈 인기 음식 목록 캐시 (KB-725)

## 스키마 변경

없음. Flyway 마이그레이션 없음. `food`·`food_view_log` 는 읽기만 하며 기존 쿼리(`findPopular`·`findByIdIn`)를 그대로 쓴다.

## 캐시 항목 (JVM 힙, 인스턴스별)

| 항목 | 타입 | 설명 |
|------|------|------|
| 키 | `Int` | 요청 레일 크기(홈은 `HomeService.POPULAR_SIZE = 10`). 사용자·언어 무관. 실제로는 항목 1개. |
| 값 | `List<Long>` | 최근 30일 조회수 내림차순·동률 id 내림차순(KB-714)으로 정렬된 공개 음식 id. 최대 키 크기. 빈 목록 허용. |
| 만료 | write 후 2일 | `expireAfterWrite`. 재배포·재시작 시 소멸. |

**불변 조건**
- 값은 `findPopular(since = now − 30일, size)` 결과의 id 순서와 같다.
- 실패한 집계는 항목이 되지 않는다. 빈 목록은 정상 항목이다.
- 같은 키에 대한 로더 실행은 동시에 1개(전역 `ReentrantLock`).

## 요청 시 조립 (캐시 대상 아님)

| 값 | 출처 | 계산 시점 |
|----|------|-----------|
| 음식 엔티티 | `findByIdIn(ids)` → 캐시 순서로 재배열. `@SQLRestriction` 이 삭제 음식 제거 | 요청마다 |
| 이름(언어) | `FoodSummaryView.from(food, lang, …)` | 요청마다 |
| 기피 성분 안전 여부 | `memberService.getAvoidance(memberId)` 로 평가 | 요청마다 |
| 북마크 여부 | `HomeService` 가 `bookmarkService.getBookmarkedFoodIds` | 요청마다 |
| 평점 | `HomeService` 가 `reviewService.getFoodRatings` | 요청마다 |

## 상태 전이

```
(없음) --첫 요청: 집계--> (유효) --2일 경과--> (만료) --다음 요청: 집계--> (유효)
(없음/만료) --집계 실패--> (없음)     ※ 다음 요청이 재시도
(유효) --invalidateAll()--> (없음)    ※ 테스트 격리용
```
