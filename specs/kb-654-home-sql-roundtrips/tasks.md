# Tasks: 홈 조회 SQL 왕복 12회→5회

**Input**: `/specs/kb-654-home-sql-roundtrips/` · Jira [KB-654](https://simhani1.atlassian.net/browse/KB-654)

**Tests**: 새 테스트 파일 없음. 리포지토리 테스트 2곳을 새 메서드로 이전(이전 직후 컴파일 실패 = Red). 홈 통합 테스트 무수정.

## Phase 1: Red — 테스트 이전

- [X] T001 [US2] `common/src/test/kotlin/com/kbap/common/domain/food/FoodJpaRepositoryTest.kt` 179행 `findRandomReadyIds(size = 10)` → `findRandom(size = 10)`
- [X] T002 [US2] `api/src/test/kotlin/com/kbap/api/scan/ScanHistoryRepositoryTest.kt` — `FoodJpaRepository` 를 `@Autowired` 로 추가하고 "최근 스캔 음식 조회" given 의 `repository.findRecentReadyFoodIds(memberId = 11L, limit = 10)` 5곳을 `foodRepository.findRecentScanned(memberId = 11L, size = 10).map { it.id }` 로 교체
- [X] T003 `./gradlew :common:compileTestKotlin :api:compileTestKotlin` 로 컴파일 실패(Red) 확인

## Phase 2: Green — 구현

- [X] T004 [P] [US1] `common/src/main/kotlin/com/kbap/common/domain/member/MemberJpaRepository.kt` 에서 `findByIdAndMemberStatus` 제거; `api/src/main/kotlin/com/kbap/api/member/MemberService.kt` 의 `getMemberOrNull` 을 `memberRepository.findByIdOrNull(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }` 로 교체
- [X] T005 [P] [US2] `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` — `findRandomReadyIds` 를 `findRandom(size): List<Food>` 네이티브(`select f.* … order by rand() limit :size`)로 교체, `findMostReviewed(size): List<Food>`·`findRecentScanned(memberId, size): List<Food>` 추가(plan D2 SQL)
- [X] T006 [P] [US2] `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt` 의 `findMostReviewedFoodIds` 삭제; `common/src/main/kotlin/com/kbap/common/domain/scan/ScanHistoryJpaRepository.kt` 의 `findRecentReadyFoodIds` 삭제
- [X] T007 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` — `reviewRepository` 생성자 파라미터·import 제거, `getRandomReadyFoods` 삭제, `getPopularFoods`/`getMostReviewedFoods`/`getRecentScannedFoods` 본문을 각각 `summaryViews(foodRepository.findRandom(size), …)`/`findMostReviewed(size)`/`findRecentScanned(memberId, size)` 로 교체(T004~T006 이후)
- [X] T008 `./gradlew :common:test :api:test` 그린(Green)

## Phase 3: 실측·정리

- [X] T009 [US1] 로컬 dev 프로필 + `SPRING_JPA_SHOW_SQL=true` 실측 — 비회원 2회, 회원 4회(기피 성분 없는 시드 회원, 있으면 5회). 변경 전 9·12
- [X] T010 홈 테스트 3파일 `git diff --stat develop` 비어 있음 확인
- [X] T011a [US2] `scan_history` 인덱스 `(member_id, created_at)` → `(member_id, food_id, created_at)` Flyway 마이그레이션 + `ScanHistory` `@Index` 동기화, api 테스트 그린, 로컬 EXPLAIN 으로 `Using temporary` 제거 확인 (plan D5)
- [X] T011 커밋 → `open-draft-pr-to-develop` → ready → Codex 리뷰
- [ ] T012 [US1] 머지 후 dev 재측정(사용자) → KB-654·KB-653 DoD 갱신

## Dependencies

T001·T002 → T003 → (T004 ∥ T005 ∥ T006) → T007 → T008 → T009·T010 → T011 → T012
