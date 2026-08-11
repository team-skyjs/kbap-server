# Tasks: 전체 리뷰 조회(무한 스크롤) 및 리뷰 응답 음식 정보 포함

**Input**: Design documents from `/specs/kb-321-review-feed-food-info/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)
- 모든 경로는 워크트리 루트(`.claude/worktrees/kb-321-review-feed-food-info/`) 기준

## Path Conventions

- 기존 모듈러 모놀리스 구조 확장 — `:common`(`common/src/{main,test}/kotlin/com/kbap/common/domain/review/`) + `:api`(`api/src/{main,test}/kotlin/com/kbap/api/review/`)

---

## Phase 1: Setup (Shared Infrastructure)

**없음** — 신규 모듈·의존성·스키마·설정이 없다(plan.md). 기존 `com.kbap.api.review` 기능 패키지와 `common.domain.review` 를 그대로 확장한다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**없음** — 모든 기반(BaseEntity 소프트삭제·`Page`/커서·차단/신고 필터·`LanguageCode`·`ImageUrls`)이 기존 코드에 이미 존재한다.

**Checkpoint**: 바로 User Story 구현 시작 가능

---

## Phase 3: User Story 1 - 전체 리뷰 피드 조회 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/v1/reviews/feed` — 음식 지정 없이 전체 리뷰를 최신순·커서 기반으로 반환. 차단 회원·신고 리뷰·삭제 음식 리뷰 제외.

**Independent Test**: 여러 음식에 리뷰를 등록한 뒤 피드를 호출하면 최신순 전체 리뷰가 반환되고, `nextCursor` 로 중복·누락 없이 다음 페이지를 받는다. (이 단계에서 응답의 `food` 는 아직 없음 — US2 에서 보강)

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [P] [US1] Red: `common/src/test/kotlin/com/kbap/common/domain/review/ReviewJpaRepositoryTest.kt` 에 `findGlobalReviewPage` given 추가 — 전체 음식 리뷰 최신순(id desc)·커서 이어받기·차단 회원 제외·신고 리뷰 제외·**소프트 삭제 음식 리뷰 제외**(R4 의 `@SQLRestriction` 실증)·빈 결과. 실행해 컴파일 실패(Red) 확인
- [X] T002 [P] [US1] Red: `api/src/test/kotlin/com/kbap/api/review/ReviewFeedControllerTest.kt` 신규 — `GET /api/v1/reviews/feed` 200 + `Page` 구조(items·hasNext·nextCursor), `lang` 누락 시 400, 미인증 401, 리뷰 없음 시 빈 목록. 실행해 Red 확인

### Implementation for User Story 1

- [X] T003 [US1] Green: `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt` 에 `findGlobalReviewPage(cursor, excludedMemberIds, excludedReviewIds, pageable)` JPQL 추가 — `exists (select 1 from Food f where f.id = r.foodId)` 포함(data-model.md). T001 Green 확인(미적용 시 명시 status 조건으로 대체 — research R4)
- [X] T004 [US1] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewListRequest.kt` 에 `FeedReviewListRequest`(lang `@field:NotBlank`·cursor) 추가
- [X] T005 [US1] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` 에 `getFeedReviewPage(viewerMemberId, lang, cursor): Page<ReviewResponse>` 추가 — 차단·신고 제외 목록 조회(-1L 센티널 규칙) 후 `findGlobalReviewPage` + 기존 `toPage` 재사용, `@Transactional(readOnly = true)`
- [X] T006 [US1] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewController.kt` 에 `GET /reviews/feed` 매핑 추가 + `api/src/main/kotlin/com/kbap/api/review/ReviewApi.kt` swagger 문서(`@Operation` 등) 추가. T002 Green 확인
- [X] T007 [US1] Refactor: 중복 제거(음식별 조회와 제외 목록 로딩 공통화 등) 후 `./gradlew :common:test :api:test --tests "com.kbap.common.domain.review.*" --tests "com.kbap.api.review.*"` 전체 Green 유지 확인

**Checkpoint**: 피드가 독립 동작 — 최신순·커서·제외 규칙 완결 (MVP)

---

## Phase 4: User Story 2 - 리뷰 카드에 음식 정보 표시 (Priority: P2)

**Goal**: `ReviewResponse` 에 중첩 `food`(foodId·name·imageUrl) 추가 — 세 목록 경로 모두 적용, 이름은 `lang` 해석(미지원→en, 번역 부재→ko), 기존 필드·author 구조 불변. 기존 목록 2개에 `lang` 필수 추가.

**Independent Test**: 음식별·내 리뷰 조회만으로 검증 가능 — 응답 각 리뷰에 `food.name`(lang 해석)·`food.imageUrl` 이 포함되고 기존 필드가 그대로인지 확인.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [ ] T008 [P] [US2] Red: `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt` 보강 — 음식별·내 리뷰 조회에 `lang` 필수(누락 400)·`food` 객체(name lang 해석, 번역 부재 ko 폴백, 미지원 코드 en 폴백, imageUrl)·기존 필드 유지·(내 리뷰) 삭제 음식 리뷰의 `food=null`. Red 확인
- [ ] T009 [P] [US2] Red: `api/src/test/kotlin/com/kbap/api/review/ReviewFeedControllerTest.kt` 보강 — 피드 응답 `food` 객체 검증(이름 해석·이미지 URL). Red 확인
- [ ] T010 [P] [US2] Red: `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt` 보강 — 생성·수정 응답의 `food=null` 유지 검증. Red 확인

### Implementation for User Story 2

- [ ] T011 [P] [US2] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewResponse.kt` 에 `ReviewFoodResponse`(foodId·name·imageUrl) 추가, `ReviewResponse.food: ReviewFoodResponse? = null` 필드 추가(`from` 파라미터 기본값 null — 생성·수정 경로 무변경)
- [ ] T012 [P] [US2] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewListRequest.kt` 의 `ReviewListRequest`·`MyReviewListRequest` 에 `lang: String`(`@field:NotBlank`) 추가
- [ ] T013 [US2] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — `toPage` 에 `lang: LanguageCode` 전달 + `FoodJpaRepository.findAllById(foodIds)` 배치 조회로 `food` 합류(`Food.displayName(lang)`·`ImageUrls.resolve`), 조회에 없는 foodId 는 `food=null`. 목록 3개 서비스 메서드 시그니처에 lang 반영
- [ ] T014 [US2] Green: `api/src/main/kotlin/com/kbap/api/review/ReviewController.kt` 기존 목록 2개에 lang 바인딩(`LanguageCode.from`) + `api/src/main/kotlin/com/kbap/api/review/ReviewApi.kt` swagger 문서 갱신. T008~T010 Green 확인
- [ ] T015 [US2] Refactor: 배치 조회 3종(작성자·좋아요·음식) 조립 정리 후 `./gradlew :api:test --tests "com.kbap.api.review.*"` Green 유지 확인

**Checkpoint**: 세 목록 경로 모두 리뷰 카드 완성 정보(음식+작성자) 반환 — US1·US2 독립 검증 완료

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T016 계약 대조: `specs/kb-321-review-feed-food-info/contracts/review-feed-api.md` 의 파라미터·응답 예시·오류 표와 구현 일치 확인(불일치 시 계약 문서 갱신)
- [ ] T017 전체 검증: `./gradlew build` (ArchUnit `arch` 태그 포함 — 컨트롤러 `/api/v` 규약·도메인 방향 맵) Green 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup/Foundational**: 태스크 없음 — US1 즉시 시작 가능
- **US1 (Phase 3)**: 선행 없음. T001·T002(Red, 병렬) → T003→T004→T005→T006(Green) → T007(Refactor)
- **US2 (Phase 4)**: US1 과 개념상 독립이나 `ReviewService.kt`·`ReviewFeedControllerTest.kt` 파일을 공유하므로 **US1 완료 후 순차 진행 권장**. T008~T010(Red, 병렬) → T011·T012(병렬)→T013→T014 → T015
- **Polish (Phase 5)**: US1·US2 완료 후. T016→T017

### Parallel Opportunities

- US1: T001 ∥ T002 (모듈이 다름 — :common 테스트 vs :api 테스트)
- US2: T008 ∥ T009 ∥ T010 (서로 다른 테스트 파일), T011 ∥ T012 (서로 다른 main 파일)

---

## Implementation Strategy

**MVP = US1** (피드 단독) → 검증 후 US2 (음식 정보 보강) → Polish. 한 task/논리 단위마다 커밋(Development Workflow). US2 까지 끝나야 Jira KB-321 DoD 4항목이 모두 충족된다(피드 API·음식 정보·author 유지·Swagger+테스트).

## Notes

- 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어) — MockMvc 는 `@AutoConfigureMockMvc`, ObjectMapper 는 `jacksonObjectMapper()` 직접 생성
- Red 단계에서 반드시 실제 실행으로 실패를 확인한 뒤 구현 시작
- 스키마 변경 없음 — Flyway 마이그레이션 금지(이 기능 범위에서 필요 없음)
