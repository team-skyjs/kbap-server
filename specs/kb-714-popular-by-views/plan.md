# Implementation Plan: 홈 인기 음식을 조회수 순으로

**Branch**: `kb-714-popular-by-views` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-714](https://simhani1.atlassian.net/browse/KB-714)

## Summary

홈 인기 음식의 무작위 선정(`order by rand()`)을 최근 30일 `food_view_log` 조회수 내림차순으로 바꾼다. 조회수 집계 서브쿼리를 `food` 에 INNER JOIN 한 네이티브 쿼리 하나가 정렬·동률 고정을 처리한다 — 조회 기록 없는 음식은 나오지 않고, 레일 크기만큼 못 채워도 그대로 둔다(보충 없음, 2026-10-04 사용자 지시). 리포지토리 메서드 하나 교체, 서비스 호출 한 줄 변경이며 응답 구조·스키마는 불변이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21, Spring Boot 4.1, Spring Data JPA, Hibernate 7, MySQL 8.4

**Storage**: 기존 `food`·`food_view_log`(인덱스 `(created_at, food_id)`) 읽기 전용. 마이그레이션 없음

**Testing**: `FoodJpaRepositoryTest` 에 `findPopular` given 추가(순서·동률·기간 밖 제외·무조회 제외·비공개 제외) — 메서드가 없어 컴파일 불가가 Red. 기존 `findRandom` 빈 목록 검증은 `findPopular` 로 이전. `HomeControllerTest`·`HomeGuestTest` 본문 무수정 — 공용 시드 `HomeTestSeed` 만 조회 로그를 함께 넣게 고친다(D6)

**Constraints**: 응답 구조 불변 · 네이티브 쿼리엔 `@SQLRestriction` 미적용 → `f.status='ACTIVE'`·`content_status='READY'` 직접 기재 · Kotlin 주석 금지 · 미사용 메서드 제거 · 격리수준·캐시·설정값 추가 없음

**Scale/Scope**: 수정 파일 4개 — `FoodJpaRepository`(+1 −1), `FoodService`(한 줄), `FoodJpaRepositoryTest`, `HomeTestSeed`

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 리포지토리 테스트를 먼저 작성 → `findPopular` 부재로 컴파일 실패(Red) → 구현 후 Green. 홈 응답 불변은 기존 통합 테스트가 판정 |
| II. Bounded Contexts | PASS | `food` 와 `food_view_log` 는 같은 food 컨텍스트. 도메인 간 의존 변화 없음 |
| III. Layered Dependency | PASS | api `FoodService` → common 리포지토리 방향 그대로 |
| IV. Persistence Ownership | PASS | 쿼리는 `FoodJpaRepository` 소유, 서비스는 `@Transactional(readOnly = true)` 유지 |
| V. Language | PASS | 무관 |

설계 후 재평가: 위반 없음. Complexity Tracking 해당 없음.

## 설계 결정

- **D1 쿼리** (`FoodJpaRepository.findPopular(since: LocalDateTime, size: Int): List<Food>`):

  ```sql
  select f.* from food f
  join (
      select v.food_id, count(*) as view_count
      from food_view_log v
      where v.created_at >= :since
      group by v.food_id
  ) x on x.food_id = f.id
  where f.status = 'ACTIVE'
    and f.content_status = 'READY'
  order by x.view_count desc, f.id desc
  limit :size
  ```

  - `findMostReviewed` 와 같은 모양이다(집계 서브쿼리 INNER JOIN).
  - 최근 30일 로그가 없는 음식은 조인에서 빠진다 → 결과가 `size` 보다 적을 수 있고 로그가 없으면 빈 목록이다.
  - 동률은 `f.id desc`(나중 등록 우선)로 고정 → 결과가 결정적이다.
  - 비공개·삭제 음식의 로그는 바깥 `where` 가 걸러낸다.
- **D2 로그 `status` 조건 생략**: `food_view_log` 는 append-only 라 삭제 행이 없다. 조건을 넣으면 `(created_at, food_id)` 커버링 스캔이 깨져 테이블 접근이 생긴다. Jira DoD 의 "소프트 삭제 조건 직접 기재"는 `food` 쪽에 적용한다.
- **D3 기준 시각**: `FoodService.getPopularFoods` 가 `LocalDateTime.now().minusDays(POPULAR_WINDOW_DAYS)` 를 넘긴다(`POPULAR_WINDOW_DAYS = 30L` 상수, `FoodService` companion). 프로젝트에 `Clock` 빈이 없고 경계 검증은 리포지토리 테스트가 `since` 를 직접 넣어 하므로 `Clock` 주입은 도입하지 않는다.
- **D4 제거**: `findRandom` 은 호출자가 `getPopularFoods` 하나뿐이라 삭제한다.
- **D5 성능 한도**: 요청마다 30일 창의 로그를 인덱스로 집계한다. 로그가 수백만 건이 되어 홈 지연이 보이면 사전 집계 테이블로 옮긴다 — 지금은 만들지 않는다.
- **D6 홈 테스트 시드**: 홈 통합 테스트는 조회 로그 없이 인기 음식 개수(7·10·`single()`)를 검증하므로, 보충이 없으면 전부 빈 목록으로 깨진다. 테스트 본문 대신 공용 시드를 고친다 — `HomeTestSeed.seedReadyFoods` 가 음식마다 `food_view_log` 1건을 함께 넣고, `HomeTestSeed.reset` 이 `food_view_log` 를 지운다(음식 id 를 재사용하므로 필수). 검증 본문은 순서에 의존하지 않아 그대로 통과한다.

## 테스트 설계 (`FoodJpaRepositoryTest`, 먼저 Red)

로그는 `FoodViewLogJpaRepository.save` 로 넣고, 기간 밖 로그는 `JdbcTemplate` 으로 `created_at` 을 과거로 UPDATE 한다(`@CreationTimestamp` 라 생성자로 못 넣는다 — 같은 모듈 `OrderJpaRepositoryTest` 선례). 각 `then` 은 `clear()` + 로그 `deleteAll()` 로 시작한다.

| 상황 | 기대 |
|---|---|
| A 3건·B 2건·C 1건 | A, B, C 순 |
| 조회수 동률 두 음식 | id 큰 쪽이 먼저 |
| 한 음식의 로그가 전부 `since` 이전 | 결과에 없음 |
| 조회 2개 + 무조회 3개, size 10 | 조회 있는 2개만 |
| 로그가 하나도 없음 | 빈 목록 |
| PENDING_REVIEW 음식에 로그 다수 | 결과에 없음 (기존 랜덤 빈 목록 검증 이전) |

## 검증

1. `./gradlew :common:test :api:test` 그린, 홈 통합 테스트 본문 무수정(시드만 변경).
2. 로컬 기동 후 음식 상세를 몇 번 조회하고 홈 호출 → 그 음식이 인기 레일 앞으로 온다(메인 `.env` 를 `set -a` 로 source, `DB_USERNAME=root DB_PASSWORD=root` 덮어쓰기).
3. 머지 후 dev 에서 상세 조회 뒤 홈 인기 레일 순서를 확인한다.

## 인덱스 확인 (2026-10-04, dev RDS MySQL 8.4.9 실측)

- 새 인덱스·마이그레이션은 필요 없다. `food_view_log.idx_food_view_log_created_food (created_at, food_id)` 가 dev 에 적용돼 있고, `EXPLAIN ANALYZE` 에서 집계 서브쿼리가 이 인덱스를 **커버링 스캔**(`Using where; Using index`)한다. `food` 는 PK 단건 조회(`eq_ref`)로 붙는다.
- 규모: 로그 134건(전부 최근 30일, 음식 44종), READY 음식 590건. 실행 1.3ms.
- 남는 비용은 `GROUP BY food_id` 임시 테이블과 조회수 정렬 파일소트다. 집계값 정렬이라 어떤 인덱스로도 없앨 수 없다. `(food_id, created_at)` 순서 인덱스는 그룹핑 임시 테이블만 없애는 대신 기간 창이 아닌 인덱스 전체를 읽게 되어 로그가 쌓일수록 불리하다 — 추가하지 않는다.
- prod 는 확인하지 않았다(같은 Flyway 마이그레이션이 적용된다).

## 산출물

`research.md`·`data-model.md`·`contracts/`·`quickstart.md` 는 만들지 않는다 — 미해결 질문·스키마 변경·API 계약 변경이 없고, 결정과 검증 절차는 이 문서에 다 있다(kb-654 선례).
