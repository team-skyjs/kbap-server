# Tasks: 리뷰 작성 자격 검증(스캔 이력) + 음식 상세 reviewEligible (KB-369)

**Input**: Design documents from `/specs/kb-369-review-eligibility/`

**Prerequisites**: plan.md, spec.md, research.md(결정 5건), data-model.md, contracts/review-eligibility.md

**Tests**: Test-First NON-NEGOTIABLE (헌법 원칙 I) — 스토리마다 Red 확인 후 Green.

**Organization**: Foundational(파생 쿼리·에러코드) → US1(작성 거절) → US2(상세 필드) → Polish.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Foundational

- [ ] T001 `common/src/main/kotlin/com/kbap/common/domain/scan/ScanHistoryJpaRepository.kt` — `existsByMemberIdAndFoodId(memberId: Long, foodId: Long): Boolean` 파생 쿼리 추가
- [ ] T002 `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` — `REVIEW_NOT_ELIGIBLE("REVIEW-004", 403, "스캔 이력이 있는 음식에만 리뷰를 작성할 수 있습니다")` 추가(`ErrorCodeStatusTest` 자동 검증)

## Phase 2: User Story 1 — 스캔하지 않은 음식 리뷰 작성 거절 (P1)

- [ ] T003 [US1] `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt` — Red: ① 스캔 이력 없는 음식 작성 → 403 REVIEW-004·리뷰 미저장 ② 매칭 성공 스캔 이력 있으면 작성 성공 ③ 타인만 스캔한 음식 → 거절 ④ 기작성 리뷰 수정은 스캔 이력 무관 성공 ⑤ 없는 음식 → FOOD-001 우선. 기존 create 경유 시나리오는 `seedScan(memberId, foodId)` 헬퍼로 시드 보강
- [ ] T004 [US1] `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — createReview 에 자격 검증(getReadyFood 다음), `ReviewApi` swagger 403 REVIEW-004 문서화. T003 Green
- [ ] T005 [US1] 리뷰 작성 API 를 쓰는 기존 테스트 시드 보강 — `ReviewListControllerTest`·`GlobalReviewListControllerTest`·`ReviewBlockFilterTest`·`FoodDetailReviewSectionTest`·`FoodDetailRatingTest`·`BookmarkControllerTest`·`ReportControllerTest`·`scenario/ScenarioApiDriver`(각 파일의 리뷰 생성 헬퍼 1곳에 스캔 시드 삽입) 후 관련 스위트 그린

## Phase 3: User Story 2 — 음식 상세 reviewEligible (P1)

- [ ] T006 [US2] 음식 상세 테스트 — Red: 회원+이력 true · 회원 무이력 false · 비회원 null (기존 상세 테스트 파일에 시나리오 추가)
- [ ] T007 [US2] `GetFoodDetailResult`·`FoodDetailResponse` 에 `reviewEligible: Boolean?` 추가, `FoodService.getDetail` 판정(`input.memberId?.let { … exists … }`), swagger 설명. T006 Green

## Phase 4: Polish

- [ ] T008 전체 `./gradlew test` 그린, quickstart 대조, 커밋(논리 단위), draft PR(본문에 FE 계약 — REVIEW-004 분기·reviewEligible 3분기 명시)

---

## Dependencies & Execution Order

- Phase 1 → US1 → US2(상세 필드는 같은 exists 쿼리 재사용) → Polish. US1 의 시드 보강(T005)이 가장 손이 크다.

## Implementation Strategy

- MVP = Phase 1+2. 단일 세션 직접 구현, 논리 단위 커밋(US1/US2 분리 가능 — 파일 겹침 없음).
