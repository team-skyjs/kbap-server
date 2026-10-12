# Implementation Plan: 홈 조회 커넥션 획득 3회→1회

**Branch**: `fix/kb716-home-single-tx` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-716](https://simhani1.atlassian.net/browse/KB-716)

## Summary

`HomeController` 가 `getHome` 뒤에 따로 부르던 `BookmarkService.getBookmarkedFoodIds`·`ReviewService.getFoodRatings` 호출을 `HomeService.getHome` 의 읽기 트랜잭션 안으로 옮긴다. 두 메서드의 `@Transactional(readOnly = true)` 는 전파 기본값(REQUIRED)이라 바깥 트랜잭션에 참여하고, 홈 한 건의 커넥션 획득이 3회에서 1회로 준다. `HomeResult` 가 `bookmarkedFoodIds`·`ratings` 를 담고 컨트롤러는 `HomeResponse.from(result, authenticated)` 매핑만 남긴다. 응답은 불변이며 기존 홈 통합 테스트 무수정 통과가 안전망이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21, Spring Boot 4.1, Spring Data JPA, Hibernate 7.4, MySQL 8.4

**Testing**: 기존 `HomeControllerTest`·`HomeGuestTest` 무수정 통과. 신규 테스트 없음 — 동작 불변 리팩터링이고 커넥션 획득 수는 dev 세션 지표로 확인한다(spec Assumptions)

**Constraints**: 응답 JSON 불변 · SQL 문 수 불변(회원 7) · `BookmarkService`·`ReviewService` 무수정 · 다른 컨트롤러(음식 목록·상세, 북마크 목록) 무수정 · Kotlin 주석 금지

**Scale/Scope**: 수정 파일 4개, 전부 `api/src/main/kotlin/com/kbap/api/home/` — `HomeService`, `HomeResult`, `HomeResponse`, `HomeController`

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 동작 불변 리팩터링. 기존 홈 통합 테스트가 응답 불변을 판정한다. 새 동작이 없어 Red 로 시작할 테스트가 없다 |
| II. Bounded Contexts | PASS | `com.kbap.api.<feature>` 간 조합이다. `common.domain` 방향 맵 대상이 아니고, 홈 기능은 이미 컨트롤러에서 bookmark·review 를 의존하고 있었다(의존 위치만 서비스로 이동) |
| III. Layered Dependency | PASS | 컨트롤러의 서비스 의존이 3개에서 1개로 준다. 컨트롤러는 서비스 호출과 DTO 매핑만 한다 |
| IV. Persistence Ownership | PASS | 리포지토리·엔티티 변화 없음. 트랜잭션 경계는 `HomeService.getHome` 이 명시 선언(기존 그대로) |
| V. Language | PASS | 무관 |

## 설계 결정

- **D1 조회 위치 이동**: `HomeService` 에 `BookmarkService`·`ReviewService` 주입. `getHome` 끝에서 세 음식 섹션의 `foodId` 목록을 만들고(컨트롤러에 있던 식 그대로) `getBookmarkedFoodIds(member?.id, foodIds)`·`getFoodRatings(foodIds)` 를 호출한다. 비회원·빈 목록 단락은 두 메서드가 이미 처리한다.
- **D2 `HomeResult` 확장**: `bookmarkedFoodIds: Set<Long>`, `ratings: Map<Long, FoodRating>` 필드 추가.
- **D3 매핑 시그니처 축소**: `HomeResponse.from(result, authenticated)` — `bookmarkedFoodIds`·`ratings` 파라미터를 없애고 `result` 에서 읽는다.
- **D4 컨트롤러 정리**: `HomeController` 생성자에서 `BookmarkService`·`ReviewService` 제거, 본문은 `getHome` → `HomeResponse.from` 뿐.
- **D5 범위 밖**: `BookmarkService`·`ReviewService` 의 트랜잭션 선언과 다른 컨트롤러의 같은 패턴은 손대지 않는다(별도 태스크).

산출물 생략: 미해결 불명점이 없어 `research.md` 없음, 엔티티·스키마 변화가 없어 `data-model.md` 없음, 응답 계약이 불변이라 `contracts/` 없음.

## 검증

1. `./gradlew :api:test` 그린 — 홈 통합 테스트 무수정.
2. dev 배포 후 `org.hibernate.session.metrics` 로그(KB-715 설정)에서 홈 요청 한 건이 `acquiring 1 JDBC connections`, statements 는 회원 기준 7 그대로인지 확인.
