# Tasks: 홈 리뷰 인기 음식 — 음식별 리뷰 수 역정규화

**Input**: Design documents from `specs/kb-726-review-count-denormalize/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (contracts/ 없음 — API 계약 불변)

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 스토리의 테스트를 구현보다 먼저 쓰고 Red 를 확인한다. Kotest 는 `--tests` 필터를 무시하므로 Red/Green 확인은 모듈 단위(`:common:test`·`:api:test`)로 돌린다. 모든 테스트는 BehaviorSpec, `given/when/then` 설명은 한국어, Kotlin 소스 주석 금지.

**Organization**: 스토리별 묶음. 단, 컬럼이 없으면 `ddl-auto=validate` 로 어떤 통합 테스트도 뜨지 않으므로 마이그레이션·엔티티 필드는 Foundational 에 둔다(US3 의 백필 검증은 Foundational 산출물 위에서 수동 확인).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 미완 의존 없음 → 병렬 가능
- **[Story]**: US1(레일 조회) / US2(카운터 증감) / US3(배포 백필)

## Path Conventions

- 마이그레이션: `api/src/main/resources/db/migration/`
- 도메인: `common/src/main/kotlin/com/kbap/common/domain/food/`
- API: `api/src/main/kotlin/com/kbap/api/review/`
- 테스트: `common/src/test/kotlin/com/kbap/common/domain/food/`, `api/src/test/kotlin/com/kbap/api/{review,admin,home}/`

---

## Phase 1: Setup

없음 — 신규 모듈·의존성·설정이 없다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 컬럼·인덱스·백필과 엔티티 매핑. 이게 없으면 api·common 통합 컨텍스트가 `validate` 에서 뜨지 않는다.

- [X] T001 Flyway 마이그레이션 `api/src/main/resources/db/migration/V2026.10.08.HH.mm.ss__food_review_count.sql` 작성(파일명 타임스탬프는 생성 시각 `date '+%Y.%m.%d.%H.%M.%S'`) — (1) `ALTER TABLE food ADD COLUMN review_count INT NOT NULL DEFAULT 0, ADD INDEX idx_food_review_count_recent (status, content_status, review_count DESC, id DESC);` (2) 백필 `UPDATE food f LEFT JOIN (SELECT food_id, COUNT(*) AS cnt FROM food_review WHERE status = 'ACTIVE' GROUP BY food_id) x ON x.food_id = f.id SET f.review_count = COALESCE(x.cnt, 0);` SQL 주석으로 KB-726 근거(요청마다 4만 행 집계 제거·절대값 대입이라 재실행 안전·구 코드는 컬럼을 모름) 기록
- [X] T002 `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt` 본문 프로퍼티(`version` 옆)에 `@Column(name = "review_count", nullable = false, columnDefinition = "int not null default 0") var reviewCount: Int = 0` 추가 — 생성자 파라미터로 넣지 않는다
- [X] T003 `./gradlew :common:test` 로 컨텍스트 기동(validate 통과)·기존 테스트 전부 Green 확인

**Checkpoint**: 스키마·엔티티 정합. 이후 스토리 테스트를 쓸 수 있다.

---

## Phase 3: User Story 1 — 홈 리뷰 인기 레일을 리뷰 수 컬럼으로 고른다 (Priority: P1) 🎯 MVP

**Goal**: `findMostReviewed` 가 food_review 를 읽지 않고 `review_count desc, id desc` 인덱스 순서로 상위 N 공개 음식을 돌려준다. 리뷰 0건 음식 제외.

**Independent Test**: `FoodJpaRepositoryTest` 에서 `review_count` 를 직접 세팅한 음식들로 정렬·제외를 검증. `HomeControllerTest` 기존 시나리오가 시드 보정만으로 통과하고 동률 시나리오가 추가된다.

### Tests for User Story 1 (Test-First — Red 먼저) ⚠️

- [X] T004 [P] [US1] `common/src/test/kotlin/com/kbap/common/domain/food/FoodJpaRepositoryTest.kt` 에 `given("리뷰 많은 음식 조회")` 추가 — `saveReady` 로 음식 저장 후 `jdbcTemplate.update("UPDATE food SET review_count = ? WHERE id = ?")` 로 값 세팅. 시나리오: (a) 리뷰 수 3·1·0 → `[3짜리, 1짜리]` 만, 0 제외 (b) 같은 리뷰 수 두 음식 → id 큰 쪽 먼저 (c) `PENDING_IMAGE`/`FAILED` 음식은 리뷰 수가 커도 제외 (d) 소프트 삭제(`delete()` 후 save) 음식 제외 (e) `size` 만큼만. **food_review 를 심지 않는다** — 새 쿼리가 리뷰 테이블을 보지 않음을 이 테스트 구조 자체가 고정한다. 기대: 현재 group by 쿼리는 food_review 가 비어 있어 빈 목록 → Red
- [X] T005 [P] [US1] `api/src/test/kotlin/com/kbap/api/home/HomeTestSeed.kt` 의 `seedReviews` 가 리뷰 INSERT 뒤 `UPDATE food SET review_count = review_count + ${memberIds.size} WHERE id = $foodId` 를 함께 실행하도록 수정 (리뷰 작성 경로를 타지 않는 시드의 카운터 보정)
- [X] T006 [US1] `api/src/test/kotlin/com/kbap/api/home/HomeControllerTest.kt` `given("홈 — 리뷰 많은 음식")` 에 `when("리뷰 수가 같은 음식이 있으면") then("id 가 큰 음식이 먼저다")` 추가 — 음식 1·2 각 리뷰 1건(서로 다른 회원) → `[2L, 1L]`. 기존 시나리오 검증 본문은 수정하지 않는다
- [X] T007 [US1] `./gradlew :common:test` 로 T004 Red 확인, `./gradlew :api:test` 로 홈 기존 시나리오 Green(시드 보정 효과)·T006 결과 기록

### Implementation for User Story 1

- [X] T008 [US1] `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` 의 `findMostReviewed` 네이티브 쿼리를 `select f.* from food f where f.status = 'ACTIVE' and f.content_status = 'READY' and f.review_count > 0 order by f.review_count desc, f.id desc limit :size` 로 교체 — 시그니처·호출자(`FoodService.getMostReviewedFoods`) 불변
- [X] T009 [US1] `./gradlew :common:test` 와 `./gradlew :api:test` Green 확인

**Checkpoint**: 홈 레일이 카운터 기준으로 동작. 카운터가 아직 갱신되지 않으므로 운영 배포는 US2 까지 묶는다.

---

## Phase 4: User Story 2 — 리뷰 작성·삭제가 음식 리뷰 수를 같은 트랜잭션에서 ±1 한다 (Priority: P1)

**Goal**: `createReview` +1, `softDelete` -1, `deleteForModeration` 의 탈퇴 분기 -1. 원자 UPDATE, `version` 불변, 비동기 없음.

**Independent Test**: 리포지토리 증감 쿼리 단위 검증 + API 경로별 `SELECT review_count FROM food` 단언.

### Tests for User Story 2 (Test-First — Red 먼저) ⚠️

- [X] T010 [P] [US2] `common/src/test/kotlin/com/kbap/common/domain/food/FoodJpaRepositoryTest.kt` 에 `given("음식 리뷰 수 증감")` 추가 — `increaseReviewCount(id)` 2회 → `review_count` 2, `decreaseReviewCount(id)` 1회 → 1, 그리고 증감 전후 `SELECT version FROM food` 가 같다(낙관 락 비관여). 메서드가 없어 컴파일 실패 = Red
- [X] T011 [P] [US2] `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt` 에 `given("음식 리뷰 수 연동")` 추가(기존 `seedFood`·`accessToken`·`createReview`·`remove` 헬퍼 재사용, 새 food id 범위 사용) — `fun foodReviewCount(foodId): Int` 를 `SELECT review_count FROM food WHERE id = ?` 로 두고 시나리오: (a) 작성 후 1 (b) 두 회원 작성 후 2 (c) 수정(PATCH) 후 불변 (d) 본인 삭제 후 0 (e) 삭제된 리뷰 재삭제(400) 후 여전히 0
- [X] T012 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminReportControllerTest.kt` — (a) 기존 `when("CONTENT_DELETED 로 처리하면")` then 에 `scalar("SELECT review_count FROM food WHERE id = $food") shouldBe "0"` 단언 추가(시드가 리뷰를 SQL 로 심으면 시드에서 `UPDATE food SET review_count = 1` 도 함께 — 기존 `seed()` 헬퍼 확인) (b) 새 `when("작성자가 탈퇴한 리뷰를 CONTENT_DELETED 로 처리하면") then("리뷰는 삭제되고 음식 리뷰 수는 1 줄며 회원 리뷰 수는 건드리지 않는다")` — `UPDATE member SET status = 'DELETED'` 로 탈퇴 처리 후 처리, `food_review.status = DELETED`·`food.review_count = 0`·`member_ranking_event` REVIEW_DELETED 0건
- [X] T013 [US2] Red 확인 — `./gradlew :common:test` 가 T010 의 미존재 메서드로 컴파일 실패하는 것을 T010 의 Red 로 기록한다(Kotest 는 컴파일 실패 시 모듈 전체가 돌지 않음). 이어서 `./gradlew :api:test` 는 T014 전까지 같은 이유로 돌지 않으므로, T014(리포지토리 메서드 2개만) 를 먼저 넣은 뒤 `:api:test` 로 T011·T012 가 카운터 0 에 머물러 Red 인 것을 확인한다

### Implementation for User Story 2

- [X] T014 [US2] `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` 에 `@Modifying(clearAutomatically = true, flushAutomatically = true) @Query("update Food f set f.reviewCount = f.reviewCount + 1 where f.id = :foodId") fun increaseReviewCount(@Param("foodId") foodId: Long): Int` 와 `- 1` 버전 `decreaseReviewCount` 추가 — where 는 PK 만(삭제된 음식도 갱신)
- [X] T015 [US2] `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — `createReview` 의 `memberService.increaseReviewCount(memberId)` 직후 `foodRepository.increaseReviewCount(foodId)`; `softDelete` 의 `memberService.decreaseReviewCount(memberId)` 직후 `foodRepository.decreaseReviewCount(review.foodId)`; `deleteForModeration` 의 `review.delete()` 분기에서 `review.delete()` 직후 `foodRepository.decreaseReviewCount(review.foodId)`. `foodId` 는 `review.delete()` 뒤 detached 상태에서도 값 필드라 안전
- [X] T016 [US2] `./gradlew :common:test` 와 `./gradlew :api:test` Green 확인 — 특히 `ReviewControllerTest` 의 기존 동시 삭제·수정/삭제 경합 시나리오와 `ReviewBotTest` 가 그대로 통과하는지

**Checkpoint**: 작성·삭제 경로 전부에서 카운터 정합. US1 + US2 가 배포 단위.

---

## Phase 5: User Story 3 — 기존 리뷰가 있는 DB 에 배포해도 리뷰 수가 처음부터 맞는다 (Priority: P2)

**Goal**: T001 의 백필이 실 데이터에서 유효 리뷰 건수와 일치함을 확인한다. 자동 테스트는 두지 않는다(research R6 — 테스트 Flyway 는 빈 DB).

**Independent Test**: 로컬 MySQL 에 리뷰가 있는 상태로 마이그레이션 적용 후 드리프트 점검 SQL 0건.

- [ ] T017 [US3] 로컬 검증 — 메인 `.env` 를 `set -a; source ../../.env; set +a` 로 읽고 `DB_USERNAME=root DB_PASSWORD=root ./gradlew :api:bootRun` 으로 기동해 마이그레이션 적용 로그 확인 후 quickstart 2-2 드리프트 점검 SQL 실행 → 0건. 리뷰가 없는 로컬이면 bootRun 전에 `food_review` 에 ACTIVE 2건·DELETED 1건을 한 음식에 넣고 백필 결과가 2 인지 확인
- [ ] T018 [US3] 로컬에서 quickstart 2-3 `EXPLAIN` 실행 — `idx_food_review_count_recent` 사용, `Using filesort` 없음 확인. 결과를 PR 본문에 첨부

**Checkpoint**: 백필·인덱스 실측 완료.

---

## Phase 6: Polish & Cross-Cutting

- [X] T019 `./gradlew build` 전체 통과(ArchUnit 포함 — `RepositoryLikeEscapeTest`·`ModuleBoundaryTest` 영향 없음 확인)
- [X] T020 [P] `../kbap-agenthub/wiki/` 에 "홈 리뷰 인기 레일 = food.review_count 역정규화, 드리프트 복구는 백필 문장 재실행, 배포 겹침 구간 재실행 필요" 를 기록하고 `INDEX.md` 한 줄 추가 후 허브 커밋(지식 위키 자동 축적 규칙)
- [ ] T021 논리 단위별 커밋(한국어 Conventional Commits, `Co-Authored-By` 라인) — 권장 분할: ① 마이그레이션+엔티티 ② 레일 조회 교체+테스트 ③ 카운터 증감+테스트 ④ 위키

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: 즉시 시작. US1·US2·US3 전부를 막는다(컬럼 없으면 컨텍스트 미기동).
- **US1 (Phase 3)**·**US2 (Phase 4)**: Foundational 뒤 서로 독립. 같은 파일(`FoodJpaRepository.kt`·`FoodJpaRepositoryTest.kt`)을 건드리므로 **한 세션에서는 순차**(US1 → US2)로 진행한다.
- **US3 (Phase 5)**: Foundational 뒤 언제든. 수동 검증이라 US1·US2 와 무관.
- **Polish (Phase 6)**: 전부 완료 후.

### Within Each User Story

- 테스트 먼저 작성 → 모듈 테스트로 Red 확인 → 최소 구현 → Green → 리팩터링 없음(변경이 작다).

### Parallel Opportunities

- T004 ∥ T005 ∥ T006 (서로 다른 파일) — 단 T006 은 T005 시드 보정 위에서 의미가 있다.
- T010 ∥ T011 ∥ T012 (서로 다른 파일).
- T020 은 코드 작업과 병렬.

---

## Implementation Strategy

### MVP = Phase 2 + US1 + US2 (한 배포 단위)

US1 만 배포하면 카운터가 백필 값에 고정돼 새 리뷰가 레일에 반영되지 않는다. US2 만 배포하면 카운터만 쌓이고 레일은 구 집계를 쓴다. 둘은 **같은 릴리스**로 나간다 — 스토리 분리는 테스트 단위를 나누기 위한 것이다.

### 배포 후

quickstart 3절 — 구 리비전 종료 후 백필 문장 재실행, 드리프트 0건 확인, dev 부하 테스트 3차(SC-005)는 사용자가 수행·기록.

---

## Notes

- 동시 작성 테스트·음수 가드·보정 배치·`FoodService` 위임 메서드·비동기 이벤트는 **만들지 않는다**(research R1·R4·R7, 스펙 Assumptions).
- 통합 테스트는 한 컨텍스트·한 DB 를 전 클래스가 공유한다 — 새 시나리오의 food/member id 는 각 클래스의 기존 id 범위 관례를 따르고 다른 클래스와 겹치지 않게 잡는다.
- `clearAutomatically = true` 뒤에는 영속 컨텍스트가 비어 있다 — 호출 위치를 T015 의 순서대로 두면 기존 회원 카운터 호출과 같은 상태에서 돈다.
