# Tasks: 리뷰 좋아요

**Input**: Design documents from `/specs/kb-271-review-like/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/review-like-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트 작성·Red 확인 후 구현한다.

**Organization**: 스토리별 독립 구현·검증 가능하게 그룹핑. 모든 테스트는 Kotest BehaviorSpec(given/when/then 한국어).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 모듈(`:common`·`:api`)·테스트 인프라(Testcontainers·MockMvc)를 그대로 확장한다. 신규 의존성·설정 없음.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `review_like` 영속 계층 — 두 스토리 모두 이 테이블·엔티티·리포지토리에 의존한다.

**⚠️ CRITICAL**: 이 페이즈 완료 전에는 어떤 스토리도 시작 불가.

- [x] T001 ReviewLike 리포지토리 통합 테스트 작성(Red 확인) in `common/src/test/kotlin/com/kbap/common/domain/review/ReviewLikeJpaRepositoryTest.kt` — Testcontainers(`MySqlContainerConfig`), 시나리오: ① `upsertActive` 신규 등록 → ACTIVE 1행 ② 같은 쌍 재호출 → 여전히 1행(중복 무해) ③ `findByReviewIdAndMemberId` 후 `delete()` 소프트삭제 → 활성 조회 null ④ 취소 후 `upsertActive` → 같은 행 부활(ACTIVE, 총 1행) ⑤ `countByReviewIds` 리뷰별 집계(DELETED 제외) ⑥ `findLikedReviewIds` 내 좋아요 Set(DELETED 제외). 컴파일 실패가 아닌 **테이블·클래스 부재로 실패**하도록 스텁 없이 작성 → 실행해 Red 확인
- [x] T002 [P] Flyway 마이그레이션 작성 in `api/src/main/resources/db/migration/V<생성 시점 로컬 시각>__review_like_table.sql` — data-model.md 의 DDL 그대로: `review_like`(id·review_id·member_id·status·created_at·updated_at), `uk_review_like_pair(review_id, member_id)`, FK → `food_review(id)`·`member(id)`, 정수 버전 금지
- [x] T003 [P] ReviewLike 엔티티 작성 in `common/src/main/kotlin/com/kbap/common/domain/review/model/ReviewLike.kt` — `BaseEntity` 상속, `reviewId: Long`·`memberId: Long`(id 값 참조, JPA 연관관계 금지), `@Table(name = "review_like")`, 도메인 메서드 없음
- [x] T004 ReviewLikeJpaRepository 작성으로 T001 Green 확인 in `common/src/main/kotlin/com/kbap/common/domain/review/ReviewLikeJpaRepository.kt` — `upsertActive`(native `INSERT ... ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=NOW(6)`, `@Modifying` — `MemberBlockJpaRepository.upsertActive` 미러)·`findByReviewIdAndMemberId`·`countByReviewIds`(JPQL group by projection)·`findLikedReviewIds`(JPQL). 작성 후 `./gradlew :common:test --tests "com.kbap.common.domain.review.ReviewLikeJpaRepositoryTest"` Green 확인 — 쿼리에 status 조건 직접 금지(`@SQLRestriction` 자동 적용)

**Checkpoint**: 영속 계층 완성 — US1·US2 시작 가능 (서로 독립).

---

## Phase 3: User Story 1 - 리뷰 좋아요 등록/취소 (Priority: P1) 🎯 MVP

**Goal**: `POST`/`DELETE /api/v1/reviews/{reviewId}/like` — 멱등 등록/취소, 없는 리뷰 400.

**Independent Test**: 등록 → 중복 등록 → 취소 → 빈 취소 → 재등록을 MockMvc 로 수행해 각 단계 응답·DB 상태가 계약대로인지 확인.

### Tests for User Story 1 (Test-First — 먼저 작성하고 Red 확인) ⚠️

- [x] T005 [US1] 좋아요 등록/취소 MockMvc 통합 테스트 작성(Red 확인) in `api/src/test/kotlin/com/kbap/api/review/ReviewLikeControllerTest.kt` — `@SpringBootTest`+`@AutoConfigureMockMvc`+BehaviorSpec, 기존 `ReviewControllerTest` 의 픽스처(회원·음식·리뷰 생성, 토큰) 패턴 재사용. 시나리오: ① 등록 200 success ② 같은 리뷰 재등록 200 + 좋아요 여전히 1건 ③ 취소 200 + 활성 좋아요 0건 ④ 좋아요 없는 상태 취소 200 no-op ⑤ 취소 후 재등록 200 + 1건 ⑥ 없는 reviewId 등록 400 `REVIEW-001` ⑦ 삭제된 리뷰 등록 400 `REVIEW-001` ⑧ 토큰 없이 등록 401 → 실행해 Red 확인

### Implementation for User Story 1

- [x] T006 [US1] ReviewService 에 좋아요 메서드 추가 in `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — `likeReview(memberId, reviewId)`: `@Transactional`, `reviewRepository.findById` orElseThrow `BusinessException(REVIEW_NOT_FOUND)` 후 `reviewLikeRepository.upsertActive` · `unlikeReview(memberId, reviewId)`: `@Transactional`, `findByReviewIdAndMemberId?.delete()`(무검증 멱등 — `unbookmark` 선례)
- [x] T007 [US1] 컨트롤러·swagger 추가로 T005 Green 확인 — `api/src/main/kotlin/com/kbap/api/review/ReviewController.kt` 에 `@PostMapping("/reviews/{reviewId}/like")`·`@DeleteMapping("/reviews/{reviewId}/like")`(`ApiPaths.V1` 베이스, `@AuthMemberId`, `ResponseEntity<BaseResponse<Unit>>`) + `api/src/main/kotlin/com/kbap/api/review/ReviewApi.kt` 에 문서 애너테이션만(`@Operation`·`@ApiResponses`·`@SecurityRequirement` — Spring 애너테이션 금지). `./gradlew :api:test --tests "com.kbap.api.review.ReviewLikeControllerTest"` Green 확인

**Checkpoint**: 좋아요 등록/취소 단독 동작 — MVP.

---

## Phase 4: User Story 2 - 리뷰 목록에서 좋아요 정보 확인 (Priority: P2)

**Goal**: 음식 리뷰 목록·내 리뷰 목록·생성/수정 응답의 각 리뷰에 `likeCount`·`likedByMe` 포함.

**Independent Test**: 리포지토리로 좋아요 픽스처를 심고 목록 API 를 호출해 각 리뷰 항목의 수·여부가 정확한지 확인 (US1 엔드포인트 불필요 — Foundational 만으로 독립 검증 가능).

### Tests for User Story 2 (Test-First — 먼저 작성하고 Red 확인) ⚠️

- [x] T008 [US2] 목록 응답 좋아요 필드 테스트 추가(Red 확인) in `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt` — 기존 스펙에 given 블록 추가: ① 좋아요 3건 리뷰 → `likeCount=3` ② 조회 회원이 누른 리뷰 `likedByMe=true`·안 누른 리뷰 `false` 혼재 목록 ③ 좋아요 0건 → `0`/`false` ④ 취소(소프트삭제)된 좋아요는 수·여부에서 제외 ⑤ `/reviews/me` 도 동일 필드 포함 → 실행해 Red 확인

### Implementation for User Story 2

- [x] T009 [US2] 응답 확장으로 T008 Green 확인 — `api/src/main/kotlin/com/kbap/api/review/ReviewResponse.kt` 에 `likeCount: Long`·`likedByMe: Boolean` 추가(`from` 파라미터 확장, swagger `@field:Schema` 포함) + `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` 의 `toPage` 에서 배치 enrich(페이지 reviewId 목록 → `countByReviewIds` Map + `findLikedReviewIds` Set, 쿼리 2개 — 리뷰별 N+1 금지. `toPage` 호출부에 viewer memberId 전달: `getMyReviewPage` 는 본인, `getFoodReviewPage` 는 viewerMemberId) + 생성/수정 단건 응답은 `likeCount=0`(생성)·집계 조회(수정)로 채움. `./gradlew :api:test --tests "com.kbap.api.review.*"` Green 확인 (기존 ReviewControllerTest·ReviewBlockFilterTest 회귀 포함)

**Checkpoint**: 두 스토리 모두 독립 동작.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T010 전체 회귀 + 계약 검증 — `./gradlew build`(ArchUnit `ModuleBoundaryTest` 포함, e2e·admin 등 전 모듈 회귀 — additive 필드로 기존 테스트가 깨지면 오버스펙 assertion 여부 먼저 의심), quickstart.md 검증 명령 수행, 태스크/논리 단위 커밋 정리

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2 (Foundational)**: 즉시 시작 가능 — T001(Red) → {T002, T003}(병렬) → T004(Green)
- **Phase 3 (US1)**·**Phase 4 (US2)**: 각각 Phase 2 완료 후 시작 — **서로 독립, 병렬 가능**
- **Phase 5 (Polish)**: US1·US2 완료 후

### User Story Dependencies

- **US1 (P1)**: Foundational 만 의존 — US2 와 파일 겹침은 `ReviewService.kt`(T006 vs T009) 하나뿐이므로 순차 권장, 논리적 의존은 없음
- **US2 (P2)**: Foundational 만 의존 — 좋아요 픽스처는 리포지토리로 직접 심어 US1 없이 검증 가능

### Parallel Opportunities

- T002(마이그레이션) ∥ T003(엔티티) — 다른 파일, T001 Red 확인 후
- T005(US1 테스트) ∥ T008(US2 테스트) — 다른 파일, Red 단계 동시 작성 가능
- US1·US2 구현은 `ReviewService.kt`·`ReviewApi.kt` 공유로 같은 파일 충돌 — 한 세션에서는 P1 → P2 순차 진행

---

## Implementation Strategy

**MVP = Phase 2 + Phase 3 (US1)**: 등록/취소만으로 배포 가능한 최소 증분. 이후 Phase 4 로 목록 노출을 얹는다. 각 태스크(또는 Red+Green 짝) 단위로 커밋한다.
