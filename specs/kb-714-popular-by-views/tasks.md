# Tasks: 홈 인기 음식을 조회수 순으로

**Input**: `/specs/kb-714-popular-by-views/` · Jira [KB-714](https://simhani1.atlassian.net/browse/KB-714)

**Tests**: 새 테스트 파일 없음. `FoodJpaRepositoryTest` 에 `findPopular` given 을 먼저 추가(메서드 부재로 컴파일 실패 = Red). 홈 통합 테스트는 본문 무수정, 공용 시드 `HomeTestSeed` 만 변경.

## Phase 1: Red — 리포지토리 테스트

- [X] T001 [US1] `common/src/test/kotlin/com/kbap/common/domain/food/FoodJpaRepositoryTest.kt` — `FoodViewLogJpaRepository`·`JdbcTemplate` 을 `@Autowired` 로 추가하고 given("findPopular — 최근 조회수 순 인기 음식") 을 추가한다. 로그는 `foodViewLogRepository.save(FoodViewLog(foodId = …))` 로 넣고, 각 then 은 `clear()` + `foodViewLogRepository.deleteAll()` 로 시작, `since = LocalDateTime.now().minusDays(30)`. 시나리오: ① A 3건·B 2건·C 1건 → `[A, B, C]` ② 조회수 동률 두 음식 → id 큰 쪽 먼저 ③ PENDING_REVIEW 음식에 로그 다수 → 결과에 없음
- [X] T002 [US2] 같은 given 에 시나리오 추가: ④ 한 음식의 로그 `created_at` 을 `jdbcTemplate.update("UPDATE food_view_log SET created_at = ? WHERE food_id = ?", 31일 전, id)` 로 밀면 결과에 없음 ⑤ 조회 2개 + 무조회 READY 3개, size 10 → 조회 있는 2개만 ⑥ 로그 없음 → 빈 목록
- [X] T003 같은 파일의 기존 `foodJpaRepository.findRandom(size = 10).shouldBeEmpty()` when("PENDING_REVIEW 만 있고 랜덤 조회하면") 블록을 삭제한다(③·⑥ 이 대체)
- [X] T004 `./gradlew :common:compileTestKotlin` 컴파일 실패(Red) 확인

## Phase 2: Green — 구현

- [X] T005 [US1] `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` — `findRandom` 을 `findPopular(@Param("since") since: LocalDateTime, @Param("size") size: Int): List<Food>` 네이티브 쿼리로 교체(plan D1 SQL: 집계 서브쿼리 INNER JOIN, `f.status='ACTIVE' and f.content_status='READY'`, `order by x.view_count desc, f.id desc`, `limit :size`)
- [X] T006 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` — `getPopularFoods` 본문을 `summaryViews(foodRepository.findPopular(LocalDateTime.now().minusDays(POPULAR_WINDOW_DAYS), size), lang, memberId)` 로 바꾸고 companion 에 `const val POPULAR_WINDOW_DAYS = 30L` 추가(companion 이 없으면 생성)
- [X] T007 [US2] `api/src/test/kotlin/com/kbap/api/home/HomeTestSeed.kt` — `reset` 목록에 `"DELETE FROM food_view_log"` 추가, `seedReadyFoods` 가 음식마다 `INSERT INTO food_view_log (food_id, member_id, status, created_at, updated_at) VALUES ($id, NULL, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))` 를 함께 실행(plan D6)
- [X] T008 `./gradlew :common:test :api:test` 그린(Green). 홈 외 테스트가 인기 음식 빈 목록으로 깨지면 그 테스트의 시드에 조회 로그를 더한다(검증 본문은 유지)

## Phase 3: 실측·정리

- [X] T009 `git diff --stat develop -- api/src/test/kotlin/com/kbap/api/home/HomeControllerTest.kt api/src/test/kotlin/com/kbap/api/home/HomeGuestTest.kt` 비어 있음 확인, `grep -rn findRandom common api` 0건 확인
- [X] T010 [US1] 로컬 bootRun(메인 `.env` source, `DB_USERNAME=root DB_PASSWORD=root`) → 음식 상세를 A 3회·B 1회 조회 → `GET /api/home` 의 `popularFoods` 가 A, B 순인지 확인
- [ ] T011 커밋 → `open-draft-pr-to-develop` → ready → Codex 리뷰
- [ ] T012 Jira KB-714 본문의 "조회수 없는 음식으로 레일 채움" 문구를 보충 철회 결정에 맞게 수정(사용자 확인 후)
- [ ] T013 [US1] 머지 후 dev 에서 상세 조회 뒤 홈 인기 레일 순서 확인

## Dependencies

T001·T002·T003 → T004 → T005 → (T006 ∥ T007) → T008 → T009·T010 → T011 → T012·T013

MVP 는 US1(T001·T003~T006)이지만 US2 는 같은 쿼리의 경계 조건이라 한 PR 로 함께 낸다.
