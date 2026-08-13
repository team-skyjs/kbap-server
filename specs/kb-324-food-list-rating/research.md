# Research: 음식 목록 응답에 리뷰 평점·개수 추가

## R1. 집계 공급 방식 — foodIds 배치 group by 쿼리

- **Decision**: `ReviewJpaRepository.aggregateRatingsByFoodIds(foodIds: List<Long>): List<FoodRatingAggregate>`(projection: foodId·average·reviewCount, `where r.foodId in :foodIds group by r.foodId`) 를 추가하고, 조립처가 `associateBy { it.foodId }` 맵으로 합류한다.
- **Rationale**: 페이지당 쿼리 1회 고정(SC-004). 기존 단건 `aggregateRating`(상세용)과 같은 JPQL 집계라 소프트 삭제 제외(`@SQLRestriction`) 규칙이 자동으로 동일하다.
- **Alternatives considered**: 목록 쿼리에 조인으로 내장(`FoodRepositoryCustomImpl` 수정 — 목록·검색·홈·북마크 쿼리가 제각각이라 4곳 수정 + keyset 쿼리 복잡화로 기각), food 테이블에 집계 컬럼 비정규화(스키마 변경·리뷰 쓰기 경로마다 갱신 필요로 기각 — 실측 성능 문제가 생기면 그때).

## R2. 적용 범위 — FoodSummaryView 조립처 5곳 전부

- **Decision**: 목록·검색(`FoodService.foodPage`)·홈 인기+최근 스캔(`HomeService`)·북마크(`BookmarkService`)·어드민(`AdminFoodService`) 전 조립처에 합류한다.
- **Rationale**: 요청 문구는 목록·홈 인기·검색이지만, 응답 스키마(`FoodSummaryResponse`)를 북마크·어드민·최근 스캔도 공유한다. 필드만 추가하고 일부 경로에서 채우지 않으면 그 화면들이 똑같은 "— · 0" 버그를 새로 얻는다.
- **Alternatives considered**: 세 경로만 채우고 나머지는 null/0(스키마 공유 화면 간 불일치로 기각).

## R3. 값 표현 — 상세 overall 과 동일 규칙 (2026-08-11 2차 개정)

- **Decision**: 두 값을 중첩 `review` 객체(`review.averageRating: Double`·`review.count: Long`)로 묶어 내린다. **리뷰 0건은 `0.0`·`0`** — 상세 `review.overall` 과 0건 포함 완전 일치. 평균은 소수 1자리 반올림(상세 `roundToFirstDecimal` 동일 공식).
- **Rationale**: 상세의 `review` 섹션과 구조 대칭 + 목록·상세 값 규약이 예외 없이 단일해진다. FE 는 `review.count == 0` 으로 "—" 를 분기한다(사용자 확정).
- **개정 이력**: 초기안은 0건 `averageRating: null`(평점 0점과 구분)이었으나, Codex 리뷰가 상세 0.0 과의 표현 불일치를 지적했고 사용자 결정으로 0.0 통일 + 객체 묶음으로 확정.

## R4. 도메인 경계 — food 가 review 를 알지 않게

- **Decision**: `FoodSummaryView.from` 은 평점 값 2개를 파라미터로 받을 뿐 review 타입을 모른다. 집계 조회·합류는 소비 계층(api 서비스·FoodService 조립부)이 수행한다.
- **Rationale**: `common.domain` 간 허용 방향 맵에 food→review 를 추가하지 않아도 된다(`ModuleBoundaryTest` 무변경). 리포지토리 직접 참조는 KB-220 규칙상 허용.
- **Alternatives considered**: FoodSummaryView 가 RatingAggregate 타입을 직접 수용(food→review 도메인 의존 발생으로 기각).

## R5. 비회원 노출 — 목록 공개·상세 blur 존치 (리뷰 후 확정)

- **Decision**: 목록·홈·검색의 평점·리뷰 수는 비회원에게도 공개한다. 상세의 리뷰 섹션 blur(비회원 0.0·blur=true)는 가입 유도 정책으로 그대로 둔다.
- **Rationale**: 홈 인기 섹션은 비회원 진입 화면이라 가리면 "— · 0" 문제가 비회원에게 재현된다. 상세 blur 는 리뷰 상세 열람 유도가 목적이고, 목록의 집계 수치 공개와 목적이 달라 공존 가능(Codex 독립 리뷰가 충돌을 지적했고 제품 결정으로 비대칭을 확정).
- **Alternatives considered**: 목록도 가림(비회원 홈 카드가 다시 비어 보여 기각), 상세 overall 공개(가입 유도 정책 완화라 별도 논의로 기각).
