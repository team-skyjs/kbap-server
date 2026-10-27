# Feature Specification: 홈 "평점 높은 음식" 섹션 (topRatedFoods)

**Feature Branch**: `feat/kb800-home-top-rated-foods`

**Created**: 2026-10-16

**Status**: Draft

**Input**: User description: "홈 API(GET /api/home)에 '평점 높은 음식' 섹션 topRatedFoods 를 X-API-Version 1.3 이상에서만 추가한다 (Jira KB-800). 1.2 이하 응답은 무변."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 홈에서 리뷰 많고 평점 높은 음식 목록 받기 (Priority: P1)

새 앱(버전 1.3)이 홈을 열면, 리뷰가 많은 순서로 정렬된 최대 20개 음식과 각 음식의 맛·서빙 속도·친절도 평균, 총평점을 한 번에 받는다. 회원·비회원 모두 같은 목록을 받는다.

**Why this priority**: 홈 하단 리뷰 무한 피드를 대체하는 섹션의 유일한 데이터 원천이다.

**Independent Test**: 리뷰를 시드한 뒤 1.3 헤더로 홈을 호출해 `topRatedFoods` 의 순서·값을 확인한다.

**Acceptance Scenarios**:

1. **Given** 리뷰 수가 다른 READY 음식들, **When** 1.3 으로 홈 조회, **Then** 리뷰 수 내림차순 → 총평점 내림차순 → foodId 내림차순으로 최대 20개가 내려간다.
2. **Given** 속도·친절도를 하나도 입력하지 않은 리뷰만 있는 음식, **When** 1.3 조회, **Then** `servingSpeed`·`staffKindness` 는 null 이고 `overallRating == taste`.
3. **Given** 삭제된 리뷰·비READY 음식·삭제 음식·리뷰 0건 음식, **When** 1.3 조회, **Then** 집계·목록에서 제외된다.
4. **Given** 비회원, **When** 1.3 조회, **Then** 같은 목록이 `bookmarked=false` 로 내려간다.
5. **Given** 후보가 하나도 없음, **When** 1.3 조회, **Then** `topRatedFoods` 는 `[]`.

---

### User Story 2 - 구버전 앱 응답 무변 (Priority: P1)

1.2 이하 앱은 기존과 동일한 응답을 받는다(`topRatedFoods` 키 자체가 없음).

**Why this priority**: 배포된 구버전 앱 호환.

**Independent Test**: 1.2 헤더로 홈을 호출해 `topRatedFoods` 키 부재를 확인한다.

**Acceptance Scenarios**:

1. **Given** 리뷰가 있는 음식, **When** 1.2 로 홈 조회, **Then** 응답 payload 에 `topRatedFoods` 키가 없다.
2. **Given** 1.3 요청, **When** 홈 조회, **Then** 1.2 의 마케팅 인기 음식 동작도 그대로 적용된다.

### Edge Cases

- 후보 21개 이상 → 20개로 자른다.
- 집계 시점과 음식 로드 시점 사이에 사라진 음식 → 목록에서 빠진다.
- 세부 평점 0 = 미입력 → 평균에서 제외. 입력 0건이면 null.
- 동률 총평점 비교는 반올림 전 값으로 한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 X-API-Version 1.3 이상 홈 응답에 `topRatedFoods` 배열(0~20개, null 아님)을 포함해야 한다.
- **FR-002**: 후보는 활성 리뷰가 1건 이상인 ACTIVE·READY 음식이며, 활성 리뷰 수 desc → 총평점 desc → foodId desc 로 정렬한다.
- **FR-003**: 각 항목은 기존 음식 카드 필드 전부 + `taste`·`servingSpeed`·`staffKindness`·`overallRating`(소수 1자리, half-up) 을 가진다.
- **FR-004**: `servingSpeed`/`staffKindness` 는 0 보다 큰 값만의 평균이며 입력이 없으면 null. `overallRating` 은 null 이 아닌 항목들의 (반올림 전) 평균을 반올림한 값이다.
- **FR-005**: 1.2 이하 응답에는 `topRatedFoods` 키가 없어야 하고 집계도 실행하지 않는다.
- **FR-006**: 집계는 홈 조회의 단일 읽기 트랜잭션 안에서 이루어지고, 북마크·리뷰 요약 배치 조회에 id 를 합쳐 각각 한 번만 호출한다.
- **FR-007**: 비회원에게도 같은 목록을 내린다.

### Key Entities

- **TopRatedFoodRating**: 음식 id, 활성 리뷰 수, 맛·속도·친절도 평균, 총평점.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 1.3 응답의 `topRatedFoods` 정렬·값이 계약 표와 100% 일치한다(통합 테스트).
- **SC-002**: 1.2 응답에 `topRatedFoods` 키가 0회 등장한다.
- **SC-003**: 홈 요청당 추가 쿼리는 1.3 에서 2개 이내, 1.2 에서 0개다.

## Assumptions

- A-1 세부 평점 0 = 미입력(평균 제외). A-2 총평점은 null 아닌 항목만의 평균. A-3 동률 3순위 foodId desc. A-4 새 버전 "1.3" 은 1.2 동작 포함. A-5 1.2 이하는 키 생략. A-6 캐시 신설 없음. (CTO 계획 `01_cto_plan.md` 가정 절)
