# Research: 음식 리스트·상세 조회 응답에 북마크 여부 포함 (KB-153)

Technical Context 에 NEEDS CLARIFICATION 0건 — 코드 조사로 모든 결정을 확정했다.

## R1. 조합 지점 — :app:api 컨트롤러

- **Decision**: bookmarked 병합을 `FoodController`(browse·search·detail)·`BookmarkController`(목록)에서 한다. 도메인 dto(`FoodSummaryView`·`GetFoodDetailResult`)는 무변경, 필드는 API 응답 DTO 에만 추가.
- **Rationale**: 의존 방향이 `bookmark → food(api)` 다 — `BookmarkService` 가 `FoodService` 를 조합한다(목록 = 음식 요약). food 쪽에서 북마크를 조회하려면 `food → bookmark` 역의존이 필요해 Gradle 순환으로 컴파일이 깨진다. 부트앱 컨트롤러는 도메인 서비스를 직접 호출하는 허용 패턴이고 app:api 는 이미 양쪽에 의존한다.
- **Alternatives considered**: (1) `FoodService` 에 병합 — 순환, 불가. (2) `:application` 조합 서비스 — 가드레일상 도메인 간 **순환 해소**가 필요할 때만 승격하는데 여기는 순환이 없다(부트앱 레벨 병합으로 충분). 기각. (3) `BookmarkService` 가 food 페이지 조회까지 대행 — 음식 탐색이 북마크 도메인 책임이 아니며 detail 까지 끌어오면 창구가 비대해진다. 기각.

## R2. 도메인 창구 — 일괄 조회 메서드 1개

- **Decision**: `BookmarkService.findBookmarkedFoodIds(memberId: Long?, foodIds: Collection<Long>): Set<Long>` 하나로 리스트(20건)·상세(1건)를 모두 처리. `memberId == null || foodIds.isEmpty()` → `emptySet()`.
- **Rationale**: "비회원 → false" 규칙이 서비스 한 곳에 남는다(컨트롤러 4곳에 null 분기 복제 방지). 상세는 `foodId in result` 로 동일 메서드 재사용 — 단건 전용 `bookmarked(memberId, foodId)` 를 따로 만들지 않는다(YAGNI). 네이밍은 컨벤션의 `findBy~` 목록 조회 계약.
- **Alternatives considered**: 단건/일괄 메서드 2개 — 기각(단건은 일괄의 특수형).

## R3. 리포지토리 쿼리 — derived query, 소프트삭제 자동

- **Decision**: `BookmarkJpaRepository.findByMemberIdAndFoodIdIn(memberId: Long, foodIds: Collection<Long>): List<Bookmark>` derived query 추가(internal).
- **Rationale**: `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 가 전 조회에 상시 적용되므로 취소(소프트삭제)된 북마크는 자동 제외 — status 조건을 손으로 달지 않는다(컨벤션). 페이지당 IN 쿼리 1회라 N+1 없음. `bookmark(member_id, food_id)` 조합은 등록 API 가 유니크하게 유지.
- **Alternatives considered**: JPQL `select b.foodId ...` 프로젝션 — 줄 수 이득 미미, derived query 가 더 단순. 기각.

## R4. 북마크 목록 응답의 bookmarked — 상수 true

- **Decision**: `BookmarkController.findBookmarks` 항목은 쿼리 없이 `bookmarked = true` 고정.
- **Rationale**: 그 목록의 항목은 정의상 전부 그 회원이 북마크한 음식이다. 자기 자신을 다시 조회하는 건 낭비.
- **Alternatives considered**: 동일 병합 로직 재사용 — 결과가 항상 true 인 쿼리를 페이지마다 1회 낭비. 기각.

## R5. 검색 응답 포함 여부

- **Decision**: 검색(browse 와 같은 `FoodSummaryResponse`)에도 동일 병합을 적용한다.
- **Rationale**: spec Assumption — 구조 공유의 자연 결과로 허용, 규칙 동일(비회원 false). 필드가 리스트에는 있고 검색에는 없으면 클라이언트가 화면별 분기를 해야 해 오히려 비싸다.
- **Alternatives considered**: 검색 제외(엄격히 티켓 문언대로) — 같은 DTO 에서 특정 API 만 필드를 빼려면 DTO 를 쪼개야 한다(비용이 더 큼). 기각.

## R6. 응답 필드 형태

- **Decision**: `bookmarked: Boolean`(non-null, 기본값 없음 — 컨트롤러가 항상 명시 전달). 필드 누락 없음.
- **Rationale**: spec FR — 비회원도 필드는 존재(false). 기본값 파라미터를 두면 병합 누락이 컴파일 타임에 안 잡히므로 두지 않는다.
- **Alternatives considered**: `Boolean?`(비회원 null) — 클라이언트 분기 유발, spec 이 명시적으로 false 고정을 요구. 기각.

## R7. 테스트 전략

- **Decision**: (1) MockMvc 통합 — 리스트·상세·검색·북마크 목록 각각 비회원/회원 케이스, 상세는 등록→취소→false 반영까지. 회원 인증·북마크 시딩은 기존 `BookmarkControllerTest` 의 `TokenIssuer` 헬퍼 + 등록 API 재사용. (2) 도메인 통합(`BookmarkServiceTest`, Testcontainers) — `findBookmarkedFoodIds` 의 null 회원 emptySet·소프트삭제 제외·요청 집합 교집합 반환.
- **Rationale**: 헌법 원칙 I — MockMvc 케이스가 Red 진입점(필드 부재로 실패). 도메인 창구는 컨트롤러 4곳이 공유하는 규칙이므로 자체 검증을 둔다.
