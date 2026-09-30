# Implementation Plan: 홈 조회 SQL 왕복 12회→5회

**Branch**: `kb-654-home-sql-roundtrips` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-654](https://simhani1.atlassian.net/browse/KB-654)

## Summary

회원 조회를 파생 쿼리에서 `findById`(영속성 컨텍스트 경유)로 바꿔 같은 트랜잭션 안의 반복 호출을 0 SQL 로 만들고, 인기·리뷰 많은·최근 스캔 음식을 `select f.*` 네이티브 쿼리로 엔티티를 정렬된 채 한 번에 받는다. 홈 한 건의 SQL 이 12회에서 5회(비회원 2회)로 준다. 응답·정렬 의미는 불변이며 기존 홈 통합 테스트 무수정 통과가 안전망이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21, Spring Boot 4.1, Spring Data JPA, Hibernate 7, MySQL 8.4

**Testing**: 기존 `HomeControllerTest`·`HomeGuestTest` 무수정. 리포지토리 테스트 2곳을 새 메서드로 이전(`FoodJpaRepositoryTest` 의 랜덤 조회, `ScanHistoryRepositoryTest` 의 최근 스캔 given). 이전한 테스트가 새 메서드 없이는 컴파일되지 않으므로 그것이 Red, 구현 후 Green. 로컬 dev 프로필 `show-sql` 로 SQL 횟수 실측

**Constraints**: 응답·정렬 불변 · 네이티브 쿼리엔 `@SQLRestriction` 미적용 → `status='ACTIVE'` 직접 기재 · `MemberService` 계약(활성만) 유지 · Kotlin 주석 금지 · 미사용 메서드 제거

**Scale/Scope**: 수정 파일 7개 — `MemberService`, `MemberJpaRepository`(파생 쿼리 제거), `FoodJpaRepository`(+3 −1), `ReviewJpaRepository`(−1), `ScanHistoryJpaRepository`(−1), `FoodService`(3 메서드 본문·생성자), 테스트 2개

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 새 리포지토리 메서드는 이전한 리포지토리 테스트가 먼저 실패(컴파일 불가)하고 구현 후 통과. 홈 응답 불변은 기존 통합 테스트가 판정 |
| II. Bounded Contexts | PASS | `FoodJpaRepository` 가 `food_review`·`scan_history` 를 네이티브로 조인 — food→review·scan 방향은 `ModuleBoundaryTest` 허용 맵 대상이 아니다(SQL 문자열, 컴파일 의존 없음). 도메인 간 컴파일 의존 변화 없음 |
| III. Layered Dependency | PASS | `FoodService` 의 `ReviewJpaRepository` 의존이 사라진다 |
| IV. Persistence Ownership | PASS | 리포지토리 위치 불변. 회원 활성 필터는 `MemberService`(도메인 서비스) 가 소유 |
| V. Language | PASS | 무관 |

## 설계 결정

- **D1 회원 1차 캐시**: `getMemberOrNull` = `memberRepository.findByIdOrNull(id)?.takeIf { it.memberStatus == ACTIVE }`. `findByIdAndMemberStatus` 는 호출자가 없어져 제거. `BaseEntity` 의 `@SQLRestriction` 은 `em.find` 에도 적용되므로 소프트 삭제 의미 유지(통합 테스트가 판정).
- **D2 네이티브 엔티티 조회** (`FoodJpaRepository`, 전부 `select f.*`, `f.status='ACTIVE' and f.content_status='READY'`, `limit :size`):
  - `findRandom(size)`: `order by rand()`
  - `findMostReviewed(size)`: 활성 리뷰를 `food_id` 로 집계한 서브쿼리 조인, `order by cnt desc, latest desc`
  - `findRecentScanned(memberId, size)`: 회원의 활성 스캔 이력을 `food_id` 로 집계(`max(created_at)`)한 서브쿼리 조인, `order by last_scanned desc`
  - 결과 순서는 SQL 이 보장 → `loadInGivenOrder` 불필요(다른 호출자 있어 메서드 자체는 유지).
- **D3 제거**: `FoodJpaRepository.findRandomReadyIds`, `ReviewJpaRepository.findMostReviewedFoodIds`, `ScanHistoryJpaRepository.findRecentReadyFoodIds`, `FoodService.getRandomReadyFoods`, `FoodService` 의 `reviewRepository` 주입. `getReadyFoodsByIds` 는 북마크·커뮤니티가 써서 유지.
- **D4 테스트 이전**: `FoodJpaRepositoryTest` 의 `findRandomReadyIds(10).shouldBeEmpty()` → `findRandom(10)`. `ScanHistoryRepositoryTest` 의 "최근 스캔 음식 조회" given 은 `FoodJpaRepository` 를 주입해 `findRecentScanned(11L, 10).map { it.id }` 로 검증(시나리오 5개 그대로).

## 검증

1. `./gradlew :common:test :api:test` 그린.
2. 로컬 dev 프로필 + `spring.jpa.show-sql=true`(환경변수 `SPRING_JPA_SHOW_SQL=true`) 로 기동, 회원 토큰으로 홈 1회 → 로그의 `select` 문 5개, 비회원 2개.
3. 머지 후 사용자 재측정(AWS 안).
