# Implementation Plan: 리뷰 목록 조회 정렬·필터 추가

**Branch**: `kb-338-review-sort-filter` | **Date**: 2026-08-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-338-review-sort-filter/spec.md` (Jira KB-338)

## Summary

`GET /api/reviews` 에 정렬 5종(LATEST 기본·RATING_DESC·RATING_ASC·FOOD_REVIEW_COUNT_DESC·HELPFUL_DESC)과 별점 구간 필터(minRating·maxRating)를 추가한다. 커서는 **(정렬 지표, id) 복합 keyset** 으로 확장하되 LATEST 는 기존 숫자 커서를 그대로 유지해 하위 호환한다. helpful 수·음식 리뷰 수는 정렬용으로만 조회 쿼리 안에 들어가고(entity join·상관 서브쿼리), 응답 조립(likeCount 별도 집계)은 불변이다. 조회 쿼리는 기존 고정 `@Query` 대신 **custom repository(동적 JPQL) 패턴**(`FoodRepositoryCustomImpl` 선례)으로 옮긴다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1 / Hibernate 6 (HQL entity join 지원)

**Primary Dependencies**: 기존 스택 — 신규 의존 없음 (QueryDSL 미도입, EntityManager 동적 JPQL)

**Storage**: 스키마 무변경 — 인덱스 추가도 이번엔 보류(아래 리스크 참조)

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`(MySQL Testcontainers) — `GlobalReviewListControllerTest`·`ReviewListControllerTest` 확장

**Target Platform**: `:api` web bootJar

**Project Type**: web-service — 조회 계약 확장(하위 호환)

**Performance Goals**: 현 리뷰 규모(수천 건 이하)에서 목록 p95 저하 없음 — 지표 정렬은 인덱스 없이 filesort 감수

**Constraints**: 파라미터 생략 시 기존 동작 완전 불변(SC-004). `GET /api/reviews/me` 는 범위 밖. 새 X-API-Version 없음

**Scale/Scope**: api.review 5~6파일 + common review 리포지토리 1파일(custom 분리) + 테스트. 도메인 모델·Flyway·batch 영향 0

## Constitution Check

- **I. Test-First**: PASS 예정 — 정렬/필터/커서 시나리오 Red → 동적 쿼리 Green. 동점 경계 페이징이 핵심 Red.
- **II. Bounded Contexts**: PASS — 리포지토리 custom 구현은 `common.domain.review` 소유(영속 소속), 정렬 enum·요청 DTO 는 `api.review`. 도메인 간 새 간선 없음(HQL 서브쿼리·entity join 은 컴파일 의존 없음 — 위키 확인).
- **III. Layered Dependency Direction**: PASS — api → common 방향 그대로.
- **IV. Persistence Ownership**: PASS — 쿼리는 리포지토리(custom impl)에, 정렬 선택·커서 해석은 api 조립 계층에. 엔티티 무변경.
- **V. Language Policy**: PASS — lang 규칙 불변.

**위반 없음.**

## Project Structure

### Documentation (this feature)

```text
specs/kb-338-review-sort-filter/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── review-list-sort-filter.md
└── tasks.md             # /speckit-tasks output
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/review/
├── ReviewSort.kt              # (신규) 정렬 5종 enum + 요청 문자열 매핑(미지원 → 400)
├── ReviewListCursor.kt        # (신규) 복합 커서 인코딩/파싱 — LATEST 는 숫자, 지표 정렬은 "{metric}_{id}"
├── ReviewListRequest.kt       # sort·minRating·maxRating 파라미터 추가(+검증)
├── ReviewListPage.kt          # (신규) 리뷰 목록 전용 봉투 — nextCursor: String? (공용 Page 는 Long 유지)
├── ReviewService.kt           # getReviewPage 시그니처 확장 — 정렬·필터·커서 조립
├── ReviewController.kt        # 파라미터 바인딩·400 검증
└── ReviewApi.kt               # swagger — 정렬/필터 허용값·커서 규칙 문서화(FR-007)

common/src/main/kotlin/com/kbap/common/domain/review/
├── ReviewRepositoryCustom.kt      # (신규) 정렬·필터·복합 커서 목록 조회 계약
├── ReviewRepositoryCustomImpl.kt  # (신규) EntityManager 동적 JPQL — 지표별 keyset
└── ReviewJpaRepository.kt         # 기존 findReviewPage 는 custom 으로 대체(삭제)
```

**Structure Decision**: 쿼리 소유는 헌법 IV 대로 `common.domain.review` 리포지토리(custom impl — `FoodRepositoryCustomImpl` 선례). "어떤 정렬로 볼 것인가"는 api 관심사라 enum·커서 코덱·요청 검증은 `api.review` 에 둔다 — 리포지토리 계약은 (정렬 키, 지표 커서 값, id 커서, 필터)를 받는 순수 파라미터.

## 핵심 설계

### 1. 정렬별 keyset 커서 (FR-005)

모든 정렬은 **(지표 desc/asc, id desc)** 복합 정렬로 순서를 결정적으로 만들고(FR-002), 커서는 마지막 행의 (지표값, id) 쌍이다:

| 정렬 | order by | 커서 조건 (다음 페이지) | 커서 형식 |
|---|---|---|---|
| LATEST (기본) | `r.id desc` | `r.id < :id` | `"{id}"` — **기존과 동일(하위 호환)** |
| RATING_DESC | `r.rating desc, r.id desc` | `rating < :m or (rating = :m and id < :id)` | `"{rating}_{id}"` |
| RATING_ASC | `r.rating asc, r.id desc` | `rating > :m or (rating = :m and id < :id)` | `"{rating}_{id}"` |
| FOOD_REVIEW_COUNT_DESC | `foodReviewCount desc, r.id desc` | 동일 패턴 (지표 = 상관 서브쿼리) | `"{count}_{id}"` |
| HELPFUL_DESC | `likeCount desc, r.id desc` | 동일 패턴 (지표 = entity join 집계) | `"{count}_{id}"` |

- 커서 파싱은 정렬 기준에 종속: LATEST 인데 `_` 포함, 지표 정렬인데 숫자 단일 → `INVALID_CURSOR`(FOOD-002, 400). 같은 형식의 다른 지표 정렬 간 교차 사용은 파싱은 되지만 순서 보장 없음(계약 문서에 명시 — 스펙 US3-3 허용 범위).
- `nextCursor` 응답 타입이 문제: 공용 `Page.nextCursor` 가 `Long` 이라 복합 커서를 담을 수 없다 → **리뷰 목록 전용 봉투 `ReviewListPage`(nextCursor: String?)** 신설. 다른 목록 API(food·bookmark·community·`/reviews/me`)는 공용 `Page` 그대로. **리뷰 목록의 nextCursor 가 number→string 으로 바뀌는 계약 변경**이므로 클라이언트 공유 필요(요청 cursor 파라미터는 원래 문자열이라 무변경).

### 2. 지표의 쿼리 내 계산 (Jira 제약 해소)

- **helpful**: `left join ReviewLike l on l.reviewId = r.id` (Hibernate 6 entity join — `@SQLRestriction` 자동 적용으로 활성 좋아요만) + `group by r.id` + `count(l)` 정렬·having 커서. 응답의 likeCount 는 기존 별도 집계 유지(조회 20건 대상 — 중복 계산이지만 응답 조립 로직 불변이 우선, 규모상 무해).
- **음식 리뷰 수**: 상관 서브쿼리 `(select count(r2) from Review r2 where r2.foodId = r.foodId)` — `@SQLRestriction` 으로 활성 리뷰만. 음식별 조회(foodId 지정)에서는 전 항목 동점 → 사실상 최신순(스펙 Assumption).
- **동적 JPQL**: 정렬 5종 × 커서 유무 × 필터 3종 조합을 고정 `@Query` 로 감당할 수 없어 custom impl 에서 문자열 조립(where·having·order 분기). 파라미터는 전부 바인딩(문자열 직접 삽입 금지).

### 3. 별점 구간 필터 (FR-003·004)

- `minRating`·`maxRating` Int?(1~5) — `@Min/@Max` + 컨트롤러에서 `min > max` 교차 검증 → 400(COMMON-002). JPQL 에 `r.rating >= / <=` 조건 추가. 모든 정렬·기존 필터와 AND 결합.

## 리스크 / 확인 사항

- **성능**: 지표 정렬(특히 helpful join+group by·food count 서브쿼리)은 인덱스를 못 타 전량 스캔+filesort 다. 현 리뷰 규모에서 감수하고 인덱스·비정규화(like_count 컬럼)는 실측 후 후속 — 구현 완료 후 `kbap-db-review` 스킬로 쿼리 검토를 돌려 확정한다.
- **`findReviewPage` 대체**: 기존 호출 2곳(`getReviewPage`·`getRecentFoodReviews`) — recentReviews 는 LATEST 고정이라 새 계약의 기본 경로를 그대로 쓴다(쿼리 이원화 금지).
- **Page 봉투 분기**: `ReviewListPage` 는 리뷰 목록 전용 — `/reviews/me` 까지 바꾸지 않는다(범위 밖, LATEST 전용이라 Long 커서 유지).
- 400 에러 코드: 커서 형식 오류는 기존 `FOOD-002`(INVALID_CURSOR) 재사용, 정렬 허용값 밖·별점 구간 오류는 `COMMON-002`(잘못된 요청) — 신규 코드 채번 없음.
