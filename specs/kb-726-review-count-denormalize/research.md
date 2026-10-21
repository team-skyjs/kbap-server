# Research: 홈 리뷰 인기 음식 — 음식별 리뷰 수 역정규화

**Date**: 2026-10-08 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

## 현재 코드 확인 결과

- **조회**: `FoodJpaRepository.findMostReviewed(size)` — 네이티브 쿼리. `food_review` 를 `status='ACTIVE'` 로 걸러 `food_id` 로 group by 한 파생 테이블을 `food` 와 조인, `review_count desc, max(review.id) desc` 정렬. 호출자는 `FoodService.getMostReviewedFoods` → `HomeService`(레일 크기 `MOST_REVIEWED_SIZE`). 홈 외 호출자 없음.
- **작성 경로**: `ReviewService.createReview` 하나. 리뷰 봇(`ReviewBotWriter`)도 이 메서드를 호출한다 → 봇 리뷰도 자동으로 +1 된다(스펙 Assumption 해소).
- **삭제 경로**: `ReviewService.deleteReview` → `softDelete`, `AdminReportService.deleteContent` → `ReviewService.deleteForModeration` → 회원 존재 시 `softDelete`, 탈퇴 시 `review.delete()` 직접. 그 외 `Review.delete()` 호출 없음. `deleteForModeration` 은 `isActive()` 가드로 재삭제를 막는다(중복 감소 없음).
- **Food 엔티티**: `@Version version` 보유(KB-226, 콘텐츠 배치 ↔ 이미지 회수 lost update 검출). `createReview` 는 `foodService.getReadyFood` 로 Food 를 영속 컨텍스트에 올린다.
- **회원 카운터 선례**: `MemberJpaRepository.increaseReviewCount/decreaseReviewCount` — `@Modifying(clearAutomatically = true, flushAutomatically = true)` JPQL `update Member m set m.reviewCount = m.reviewCount + 1 where m.id = :memberId`. `createReview` 는 이 호출을 `reviewRepository.save` **앞**에서, `softDelete` 는 `review.delete()` **뒤**에서 한다.
- **테스트 시드**: `HomeTestSeed.seedReviews` 가 `food_review` 를 SQL 로 직접 심는다(`HomeControllerTest` 4곳). 그 외 `food_review` 를 SQL 로 심는 테스트 9개는 리뷰 인기 레일을 검증하지 않는다. `food` INSERT 는 전부 컬럼 명시형이라 `DEFAULT 0` 컬럼 추가에 영향 없다.
- **손스텁 CREATE TABLE**: `api`·`common`·`batch` 테스트 소스 어디에도 없다. 테스트 스키마는 Flyway(`flyway.enabled: true`) + `ddl-auto: validate`. Jira 의 "scan 손스텁" 항목은 해당 없음 → tasks 에 넣지 않는다.
- **`ReviewService` 의존**: `foodRepository: FoodJpaRepository` 가 이미 주입돼 있다.

## R1. 동기·원자 UPDATE (비동기 이벤트 없음)

- **Decision**: 리뷰 작성·삭제 트랜잭션 안에서 `FoodJpaRepository.increaseReviewCount(foodId)` / `decreaseReviewCount(foodId)` 를 호출한다. JPQL `update Food f set f.reviewCount = f.reviewCount + 1 where f.id = :foodId`, `@Modifying(clearAutomatically = true, flushAutomatically = true)`.
- **Rationale**: 같은 트랜잭션이라 리뷰 롤백 시 카운터도 되돌아간다. 벌크 JPQL UPDATE 는 `@Version` 을 올리지 않아(`update versioned` 가 아님) 동시 작성·콘텐츠 배치와 충돌하지 않는다. PK 1행 UPDATE 라 응답 지연이 없다. 회원 카운터와 같은 패턴. 사용자가 2026-10-08 동기 방식을 재확인했다.
- **Alternatives considered**: `@Async @EventListener` — 롤백 후 카운터만 증가·리스너 실패로 영구 드리프트(Jira 명시 기각). `@TransactionalEventListener(AFTER_COMMIT)` + 동기 — 커밋 뒤라 카운터 UPDATE 실패 시 복구 불가, 이득 없음. 엔티티 load-save(`food.reviewCount++`) — `@Version` 때문에 동시 작성 중 하나가 `OptimisticLockException` 으로 실패.
- **호출 위치**: `createReview` — `memberService.increaseReviewCount(memberId)` 바로 다음(save 앞 — 기존 순서와 동일하게 clear 가 save 전에 일어난다). `softDelete` — `memberService.decreaseReviewCount(memberId)` 바로 다음. `deleteForModeration` 의 `review.delete()` 분기 — `review.delete()` 다음(`flushAutomatically` 가 삭제를 먼저 flush 한다).
- **clearAutomatically 영향**: `softDelete` 는 이미 회원 감소 호출로 컨텍스트를 비운 뒤 detached `review` 의 `id`·`foodId` 만 읽는다(값 타입 필드 — 문제 없음). `deleteForModeration` 의 탈퇴 분기는 지금까지 flush 없이 dirty checking 에 맡겼는데, 감소 호출의 `flushAutomatically` 가 `review.delete()` 를 먼저 flush 하므로 결과 동일.

## R2. 인덱스 컬럼 순서

- **Decision**: `idx_food_review_count_recent (status, content_status, review_count DESC, id DESC)`.
- **Rationale**: 새 쿼리는 `where status='ACTIVE' and content_status='READY' and review_count > 0 order by review_count desc, id desc limit 10` 이다. 등치 조건 2개를 인덱스 앞에 두면 그 범위 안에서 정렬 순서가 인덱스 순서와 일치해 **상위 10행만 읽고 멈춘다**. Jira 가 적은 `(review_count DESC, id DESC)` 만 두면 인덱스 순서로 읽되 행마다 본 테이블에서 `status`·`content_status` 를 확인해야 하고, 리뷰 있는 비공개 음식이 상위에 끼면 더 읽는다. 컬럼 2개 추가는 인덱스 크기(4k 행)에 의미가 없다. MySQL 8 은 내림차순 인덱스를 지원한다(`idx_food_review_food_recent (food_id, id DESC)` 선례).
- **Alternatives considered**: `(review_count DESC, id DESC)` — Jira 원안. 동작은 같고 edge case 에서만 덜 효율적. 구현 중 EXPLAIN 으로 `Using index condition`·`Backward index scan` 없이 10행 읽기를 확인한다(quickstart).
- **배포 겹침**: 구 코드의 group by 쿼리는 새 인덱스와 무관하게 그대로 동작한다(가산 변경).

## R3. `review_count > 0` 조건

- **Decision**: 새 `findMostReviewed` 에 `f.review_count > 0` 을 둔다.
- **Rationale**: 기존 쿼리는 리뷰 없는 음식을 내부 조인으로 제외했고, `HomeControllerTest` 첫 시나리오("리뷰 0건 음식은 빠진다")가 이를 고정한다. 스펙 US1 시나리오 3(리뷰 없으면 빈 배열)도 같은 뜻. 범위 조건이 정렬 컬럼에 걸려 인덱스 순서 읽기를 깨지 않는다.
- **Alternatives considered**: 조건 없이 상위 10개 — 리뷰 0건 음식이 레일을 채워 기존 동작·테스트가 깨진다.

## R4. `FoodService` 를 거치지 않는다

- **Decision**: `ReviewService` 가 `foodRepository.increaseReviewCount/decreaseReviewCount` 를 직접 호출한다.
- **Rationale**: `MemberService.increaseReviewCount` 는 0건 갱신(비활성 회원)을 예외로 바꾸는 도메인 분기가 있어 서비스 메서드가 됐다. 음식은 `getReadyFood` 선검증 뒤라 0건이 날 수 없고 분기도 없다(스펙 Edge Case) — 서비스 메서드를 만들면 위임 전용이 된다(ADR-0014 금지). `ReviewService` 는 이미 `foodRepository` 를 쓴다.
- **Alternatives considered**: `FoodService.increaseReviewCount` — 선례와 대칭이지만 본문이 한 줄 위임. 기각.

## R5. 백필 문장

- **Decision**:
  ```sql
  UPDATE food f
  LEFT JOIN (
      SELECT food_id, COUNT(*) AS cnt FROM food_review WHERE status = 'ACTIVE' GROUP BY food_id
  ) x ON x.food_id = f.id
  SET f.review_count = COALESCE(x.cnt, 0);
  ```
- **Rationale**: 절대값 대입이라 **몇 번 실행해도 결과가 같다**. 블루/그린 배포 중 구 인스턴스가 만드는 리뷰(카운터 미반영)와 운영 중 어떤 이유로 생긴 드리프트를 **같은 문장을 다시 실행**해 복구한다(Jira: "드리프트 복구용 주기 배치는 만들지 않는다 — 같은 UPDATE 수동 재실행"). `LEFT JOIN + COALESCE` 라 리뷰가 전부 삭제된 음식도 0 으로 돌아온다. 4k 행 UPDATE 는 수십 ms.
- **Alternatives considered**: `INNER JOIN` 버전 — 첫 실행엔 충분하나 재실행 시 "리뷰 전부 삭제된 음식" 이 복구되지 않는다.
- **배포 절차 함의**: 신 리비전이 전부 트래픽을 받은 뒤(구 리비전 종료 후) 운영자가 이 문장을 한 번 더 실행한다 — quickstart 에 적는다. 겹침 구간이 짧아 드리프트는 보통 0~수 건이고, 인기 10개 순서를 바꿀 정도가 아니므로 재실행을 잊어도 기능 장애는 아니다.

## R6. 백필 자동 테스트를 두지 않는다

- **Decision**: 마이그레이션 백필은 자동 테스트 대상이 아니다. quickstart 의 드리프트 점검 SQL(음식별 `review_count` vs `COUNT(food_review ACTIVE)` 불일치 0건)로 로컬·dev 에서 확인한다.
- **Rationale**: 테스트 컨텍스트의 Flyway 는 빈 DB 에 돌아 백필할 행이 없다. 마이그레이션 파일을 리소스 경로로 읽어 UPDATE 만 재실행하는 테스트는 파일명 결합 함정(CLAUDE.md "시드-동기화 테스트 ↔ 마이그레이션 파일명 결합")을 만든다. 문장이 하나고 R5 의 재실행 점검으로 같은 문장을 실 데이터에서 검증한다.
- **스펙 반영**: FR-012 의 "백필 후 리뷰 수 = 유효 리뷰 건수" 항목을 자동 테스트에서 quickstart 수동 검증으로 옮긴다(spec.md 수정).

## R7. 동시 작성 테스트를 두지 않는다

- **Decision**: 같은 음식에 N 명 동시 작성 테스트는 작성하지 않는다.
- **Rationale**: CLAUDE.md 동시성 수위 — 스레드 동시 실행 테스트는 명시 요구된 치명 경로에만. Jira DoD 테스트 목록에 없다. 설계 보장(벌크 UPDATE 가 `version` 을 안 올림)은 `FoodJpaRepositoryTest` 에서 "증가 후 `version` 불변" 단언으로 고정한다 — 이것이 충돌 없음의 근거다.
- **Alternatives considered**: `ReviewControllerTest` 의 기존 executor 패턴으로 5명 동시 작성 — 가능하지만 요구되지 않은 테스트.

## R8. 엔티티 필드 위치·마이그레이션 형식

- **Decision**: `Food.reviewCount: Int = 0` 을 생성자 파라미터가 아니라 본문 프로퍼티로 둔다(`version`·`ingredientsAssessed` 와 같은 자리). `@Column(name = "review_count", nullable = false, columnDefinition = "int not null default 0")`.
- **Rationale**: 생성자 호출부(`Food.failed`, 테스트 다수)를 건드리지 않는다. `ddl-auto=validate` 가 컬럼 존재·타입만 검사하므로 `columnDefinition` 은 선례(`version`) 표기를 따른다.
- **마이그레이션 파일명**: `V2026.10.08.HH.mm.ss__food_review_count.sql` — 생성 시각 timestamp. 내용은 ALTER(컬럼+인덱스) 1문장 + 백필 UPDATE 1문장. SQL 주석으로 KB-726 근거를 남긴다(SQL 주석은 규약 밖).
- **코드-스키마 공존**: 구 코드는 새 컬럼을 모르고(`DEFAULT 0` 이라 INSERT 영향 없음), 구 `findMostReviewed` 는 새 인덱스와 무관. 신 코드는 컬럼 없는 구 스키마 위에서 부팅하지 않는다(validate) — Flyway 가 부팅 전에 돌므로 순서가 보장된다.

## 해소된 스펙 Assumptions

| 가정 | 결과 |
|------|------|
| scan 손스텁 CREATE TABLE 존재 여부 | 없음. 작업 없음. |
| 리뷰 봇 작성 경로 | `ReviewService.createReview` 경유 — 추가 작업 없음. |
| 홈 테스트 시드 보정 | `HomeTestSeed.seedReviews` 에 `UPDATE food SET review_count = review_count + N` 한 줄 추가. 검증 본문 무수정. |
