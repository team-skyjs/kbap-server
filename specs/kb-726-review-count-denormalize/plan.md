# Implementation Plan: 홈 리뷰 인기 음식 — 음식별 리뷰 수 역정규화

**Branch**: `feat/kb726-review-count-denormalize` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-726](https://simhani1.atlassian.net/browse/KB-726)

**Input**: Feature specification from `specs/kb-726-review-count-denormalize/spec.md`

## Summary

홈의 "리뷰 많은 음식" 레일이 요청마다 돌리는 `FoodJpaRepository.findMostReviewed`(food_review 전체 group by + filesort)를 **`food.review_count` 역정규화 컬럼 + 복합 인덱스 순서 읽기**로 바꾼다. 카운터는 리뷰 작성 시 +1, 소프트 삭제 시 -1 을 **리뷰 트랜잭션 안에서 원자 UPDATE(`review_count = review_count ± 1`)** 로 갱신한다 — Food 의 `@Version` 을 건드리지 않아 동시 작성에 낙관 락 충돌이 없고, 비동기 이벤트가 아니라 롤백 시 함께 되돌아간다. 마이그레이션 한 파일이 컬럼·인덱스·ACTIVE 리뷰 집계 백필을 수행한다. 홈 응답 계약은 바뀌지 않고 동률 규칙만 `max(review.id) desc` → `food.id desc` 로 단순화된다. 조회수 인기 레일(KB-725 캐시)은 손대지 않는다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA(`@Modifying` JPQL 벌크 UPDATE — `MemberJpaRepository.increaseReviewCount` 선례), Flyway. 신규 의존성 없음.

**Storage**: MySQL — `food` 에 `review_count INT NOT NULL DEFAULT 0` 컬럼과 인덱스 `idx_food_review_count_recent (status, content_status, review_count DESC, id DESC)` 추가. 백필은 마이그레이션 안의 UPDATE 한 문장.

**Testing**: Kotest BehaviorSpec — 리포지토리 테스트는 `:common` `FoodJpaRepositoryTest`(`@SpringBootTest` + `MySqlContainerConfig`), API 경로 테스트는 `@IntegrationTest`(`ReviewControllerTest`·`AdminReportControllerTest`·`HomeControllerTest`) 확장. 신규 테스트 클래스 없음.

**Target Platform**: api bootJar(ECS, 운영 2대·dev 1대). 블루/그린 배포 중 구 코드가 신 스키마 위에서 돈다.

**Project Type**: web-service (모듈러 모놀리스 `:common` + `:api`)

**Performance Goals**: 홈 1건당 리뷰 인기 레일 DB 비용 = 인덱스 순서 읽기 10행 + PK 행 조회 10회(수 ms). 리뷰 작성·삭제에 PK 1행 UPDATE 1회 추가.

**Constraints**: 카운터 갱신은 리뷰 트랜잭션 안·동기·원자 UPDATE(엔티티 load-save 금지 — `@Version` 충돌). 비동기 이벤트·보정 배치·음수 가드 금지(스펙 Assumptions). 트랜잭션 격리수준 불변.

**Scale/Scope**: 변경 파일 — 마이그레이션 신규 1, `Food` 엔티티 +1 필드, `FoodJpaRepository` 쿼리 교체 1 + 신규 2, `ReviewService` 호출 3줄, 테스트 수정 4(`FoodJpaRepositoryTest`·`ReviewControllerTest`·`AdminReportControllerTest`·`HomeControllerTest`) + 시드 헬퍼 1(`HomeTestSeed.seedReviews`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 리포지토리(정렬·0건 제외·비공개 제외·증감이 version 을 안 올림)·작성 +1·본인 삭제 -1·관리자 삭제 -1(회원 있음/탈퇴)·재삭제·수정 불변·홈 동률 순서를 구현 전 Red 로 작성한다. 기존 홈 테스트는 시드 헬퍼 보정만, 검증 본문 무수정. |
| II. Bounded Contexts | PASS | 컬럼·쿼리는 `common.domain.food` 소유. `ReviewService`(api.review) → `FoodJpaRepository` 참조는 이미 존재하는 의존(`foodRepository` 필드). 도메인 간 허용 맵 변경 없음. |
| III. Dependency Direction | PASS | `api.review` → `common.domain.food` 방향만. common 에 Spring 외 신규 의존 없음. |
| IV. Persistence Ownership | PASS | 증감 쿼리는 소유 도메인 리포지토리(`FoodJpaRepository`)에 두고 소비자(`ReviewService`)가 직접 호출 — 위임 전용 창구 메서드를 `FoodService` 에 만들지 않는다. 트랜잭션 경계는 기존 `createReview`/`deleteReview`/`deleteForModeration` 의 `@Transactional` 이 소유. JPA 연관관계 없음(id 참조). 스키마는 Flyway(api). |
| V. Language Policy | PASS | 해당 없음 — lang 처리 불변. |
| 추가 제약 | PASS | API 응답 계약 불변. 엔티티를 응답에 노출하지 않음(기존 `FoodSummaryView` 경유). |

**Post-design re-check (Phase 1 후)**: 위 판정 유지. 신규 추상화·설정값·이벤트·배치 없음. 유일한 설계 편차는 Jira 가 적은 인덱스 `(review_count DESC, id DESC)` 앞에 등치 조건 컬럼 `(status, content_status)` 를 붙인 것 — [research.md R2](research.md) 에 근거.

## Project Structure

### Documentation (this feature)

```text
specs/kb-726-review-count-denormalize/
├── spec.md              # 요구사항
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정·대안
├── data-model.md        # Phase 1 — 컬럼·인덱스·증감 규칙·백필
├── quickstart.md        # Phase 1 — 검증 절차(테스트·로컬·배포 후 드리프트 점검·dev 부하 테스트)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 가 생성
```

contracts/ 는 만들지 않는다 — 외부 API 계약(경로·요청·응답 스키마) 변경이 없다.

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V2026.10.08.HH.mm.ss__food_review_count.sql        # 신규 — 컬럼 + 인덱스 + 백필

common/src/main/kotlin/com/kbap/common/domain/food/
├── model/Food.kt                                      # 수정 — reviewCount 필드(본문 프로퍼티, version 옆)
└── FoodJpaRepository.kt                               # 수정 — findMostReviewed 쿼리 교체, increaseReviewCount/decreaseReviewCount 추가

api/src/main/kotlin/com/kbap/api/review/
└── ReviewService.kt                                   # 수정 — createReview +1, softDelete -1, deleteForModeration 의 review.delete() 분기 -1

common/src/test/kotlin/com/kbap/common/domain/food/
└── FoodJpaRepositoryTest.kt                           # 수정 — findMostReviewed 정렬/제외, 증감 쿼리

api/src/test/kotlin/com/kbap/api/
├── review/ReviewControllerTest.kt                     # 수정 — 작성 +1, 수정 불변, 본인 삭제 -1, 재삭제 불변
├── admin/AdminReportControllerTest.kt                 # 수정 — CONTENT_DELETED -1(회원 있음), 작성자 탈퇴 분기 -1
├── home/HomeControllerTest.kt                         # 수정 — 동률(id desc) 시나리오 추가
└── home/HomeTestSeed.kt                               # 수정 — seedReviews 가 food.review_count 도 올린다
```

**Structure Decision**: 기존 파일만 수정하고 신규는 마이그레이션 1개뿐이다. 카운터 증감은 `MemberJpaRepository.increaseReviewCount` 와 같은 `@Modifying(clearAutomatically = true, flushAutomatically = true)` JPQL 벌크 UPDATE 로 `FoodJpaRepository` 에 둔다. `ReviewService` 는 이미 `foodRepository` 를 주입받고 있어 새 의존이 없다.

## Design Decisions (요약 — 상세는 research.md)

| # | 결정 | 한 줄 근거 |
|---|------|-----------|
| R1 | 동기·원자 UPDATE, 비동기 이벤트 없음 | 롤백 정합 + PK 1행 UPDATE 라 비용 없음(사용자 2026-10-08 확정) |
| R2 | 인덱스 `(status, content_status, review_count DESC, id DESC)` | 쿼리의 등치 2개를 앞에 두면 상위 10행만 읽고 끝난다 |
| R3 | `findMostReviewed` 에 `review_count > 0` 조건 유지 | 기존 동작(리뷰 0건 음식 제외)·기존 홈 테스트 보존 |
| R4 | 증감은 `FoodJpaRepository` 직접 호출, `FoodService` 경유 안 함 | 0건 분기가 없어 위임 전용 메서드가 된다(ADR-0014) |
| R5 | 백필은 `LEFT JOIN … COALESCE(…, 0)` 절대값 UPDATE | 재실행 가능 — 배포 겹침 구간 드리프트를 같은 문장으로 복구 |
| R6 | 백필 자동 테스트 없음, quickstart 의 드리프트 점검 SQL 로 대체 | Flyway 는 빈 DB 에 돌아 테스트 불가, 파일명 결합 테스트는 함정 |
| R7 | 동시 작성 테스트 없음 | 치명 경로 아님(CLAUDE.md 동시성 수위). 보장은 UPDATE 문 형태 + version 불변 리포지토리 테스트로 |

## Complexity Tracking

위반 없음 — 기록할 항목 없다.
