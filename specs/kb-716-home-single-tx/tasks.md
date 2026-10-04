# Tasks: 홈 조회 커넥션 획득 3회→1회

**Input**: `/specs/kb-716-home-single-tx/` · Jira [KB-716](https://simhani1.atlassian.net/browse/KB-716)

**Tests**: 새 테스트 없음. 동작 불변 리팩터링이라 기존 홈 통합 테스트(`HomeControllerTest`·`HomeGuestTest`) 무수정 통과가 판정 기준이다.

## Phase 1: 구현 (US1)

- [X] T001 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeResult.kt` — `bookmarkedFoodIds: Set<Long>`, `ratings: Map<Long, FoodRating>`(`com.kbap.api.review.FoodRating`) 필드 추가
- [X] T002 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeService.kt` — 생성자에 `BookmarkService`·`ReviewService` 주입. `getHome` 에서 `(popularFoods + mostReviewedFoods + recentScans.map { it.summary }).map { it.foodId }` 로 `foodIds` 를 만들고 `bookmarkService.getBookmarkedFoodIds(member?.id, foodIds)`·`reviewService.getFoodRatings(foodIds)` 결과를 `HomeResult` 에 담는다(T001 이후)
- [X] T003 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeResponse.kt` — `from(result, authenticated)` 로 시그니처 축소, `bookmarkedFoodIds`·`ratings` 는 `result` 에서 읽는다. 미사용 import 정리(T001 이후)
- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeController.kt` — 생성자에서 `BookmarkService`·`ReviewService` 와 import 제거, 본문은 `homeService.getHome(...)` → `HomeResponse.from(result, authenticated = memberId != null)` 만 남긴다(T002·T003 이후)

## Phase 2: 검증·마무리

- [X] T005 `./gradlew :api:test` 그린
- [X] T006 `git diff --stat develop -- api/src/test/kotlin/com/kbap/api/home` 이 비어 있음 확인(홈 테스트 무수정)
- [ ] T007 커밋 → `open-draft-pr-to-develop` → ready → Codex 리뷰
- [ ] T008 [US1] 머지·dev 배포 후 `org.hibernate.session.metrics` 로그에서 홈 요청의 `acquiring 1 JDBC connections`, statements 7(회원) 확인(사용자) → KB-716 DoD 갱신

## Dependencies

T001 → (T002 ∥ T003) → T004 → T005 → T006 → T007 → T008
