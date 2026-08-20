# Implementation Plan: 리뷰 작성 자격 검증(스캔 이력) + 음식 상세 reviewEligible

**Branch**: `kb-369-review-eligibility` | **Date**: 2026-08-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-369-review-eligibility/spec.md`

## Summary

리뷰 작성(POST /api/reviews)에 "요청 회원의 활성 스캔 이력에 해당 음식 존재" 자격 검증을 추가하고(위반 시 신규 `REVIEW-004`), 음식 상세(GET /api/foods/{foodId}) 응답에 같은 기준의 `reviewEligible`(회원 true/false·비회원 null)을 추가한다. 판정은 양쪽 모두 `scan_history` 에 대한 단일 exists 쿼리 — `ScanHistoryJpaRepository.existsByMemberIdAndFoodId` 파생 쿼리 하나를 신설해 공유한다. 수정(PATCH)은 재검증하지 않는다(본인 리뷰 검증이 자격을 전이 보장).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 · Spring Data JPA (기존)

**Storage**: MySQL — 기존 `scan_history` 테이블 읽기만. 스키마 변경 없음(Flyway 마이그레이션 없음)

**Testing**: Kotest BehaviorSpec + MockMvc + MySQL Testcontainers (기존 패턴)

**Target Platform**: `:api` web bootJar

**Project Type**: 모듈러 모놀리스 — `:common`(리포지토리 파생 쿼리 1개) + `:api`(서비스 검증·응답 필드)

**Performance Goals**: 리뷰 작성·상세 조회에 exists 쿼리 1회 추가 — `idx_scan_history_recent(member_id, created_at)` 의 member_id 프리픽스로 회원당 이력 수준 스캔. 신규 인덱스 불필요(회원당 스캔 수 소규모)

**Constraints**: 기존 리뷰 작성 테스트 다수가 스캔 이력 없이 작성하므로 전부 시드 보강 필요(아래 Blast Radius)

**Scale/Scope**: 파생 쿼리 1 + 에러코드 1 + 서비스 검증 1줄 + 응답 필드 1 + 테스트 보강

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 스토리별 Red→Green — 자격 거절·상세 필드 테스트 선행 |
| II. Bounded Contexts | PASS | review→scan 참조는 id 기반 exists 조회. `ModuleBoundaryTest` 허용 맵에 review→scan 방향 추가 필요 여부 확인(api 기능 패키지에서의 리포지토리 사용은 맵 대상 아님 — `com.kbap.api.review` 는 도메인 패키지가 아니다) |
| III. Dependency Direction | PASS | api 기능 패키지 → common.domain 리포지토리 직접 사용(KB-220 규약) |
| IV. Persistence Ownership | PASS | 파생 쿼리는 `common.domain.scan` 소유. 창구 서비스 신설 없음 |
| V. Language Policy | PASS | 언어 무관 기능 |

**게이트 통과.** 도메인 간 의존 주의점: 검증·판정 로직은 `com.kbap.api.{review,food}` (api 기능 패키지)에 두므로 `common.domain` 간 방향 맵은 건드리지 않는다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-369-review-eligibility/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/review-eligibility.md
└── tasks.md
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── core/error/ErrorCode.kt                     # REVIEW_NOT_ELIGIBLE("REVIEW-004", 403) 추가
└── domain/scan/ScanHistoryJpaRepository.kt     # existsByMemberIdAndFoodId 파생 쿼리 추가

api/src/main/kotlin/com/kbap/api/
├── review/ReviewService.kt                     # createReview 에 자격 검증(스캔 이력 exists)
├── review/ReviewApi.kt                         # swagger — 403 REVIEW-004 문서화
├── food/FoodService.kt                         # getDetail 에 reviewEligible 판정
├── food/GetFoodDetailResult.kt                 # reviewEligible: Boolean? 추가
├── food/FoodDetailResponse.kt                  # reviewEligible: Boolean? 추가 + swagger
└── food/FoodApi.kt                             # swagger 설명 갱신(필요 시)

api/src/test/kotlin/com/kbap/api/
├── review/ReviewControllerTest.kt              # 자격 거절·통과 시나리오 + 기존 작성 시드 보강
├── food/FoodDetailControllerTest.kt(관련 상세 테스트)  # reviewEligible 3분기
└── (리뷰 작성 API 를 쓰는 전 테스트)              # 스캔 이력 시드 보강 — 아래 Blast Radius
```

**Structure Decision**: 판정 공유는 리포지토리 파생 쿼리 수준에서 한다 — `existsByMemberIdAndFoodId` 하나를 ReviewService·FoodService 가 각자 호출(FR-005 의 "같은 기준"은 같은 쿼리로 보장). 별도 도메인 서비스·헬퍼 클래스는 만들지 않는다(위임 1줄뿐 — KB-220 창구 서비스 금지).

## 핵심 설계 결정

1. **판정 기준 = `scan_history` exists (member_id, food_id)**: `@SQLRestriction("status='ACTIVE'")` 가 활성 이력만 보게 하고, `food_id IS NULL`(매칭 실패 스캔) 행은 foodId 조건에 자연히 걸러진다. 스펙의 "활성·매칭 성공 이력" 그대로.
2. **에러 코드 `REVIEW-004` / HTTP 403**: 인증은 됐으나 자격이 없는 상태 — REVIEW-002(403, 본인 아님)와 같은 축. FE 는 코드로만 분기.
3. **검증 위치·순서**: `ReviewService.createReview` 에서 `getReadyFood`(음식 오류 우선 — FR-006) 다음, 이미지 검증 앞에 둔다. `updateReview` 는 손대지 않는다.
4. **상세 응답**: `GetFoodDetailResult`·`FoodDetailResponse` 에 `reviewEligible: Boolean?` — `input.memberId?.let { scanHistoryRepository.existsByMemberIdAndFoodId(it, food.id) }`. FoodService 는 이미 scanHistoryRepository 를 주입받고 있다.
5. **인덱스 추가 없음**: exists 는 `idx_scan_history_recent(member_id, …)` member_id 프리픽스로 회원당 이력만 훑는다(회원당 수십 건 수준). 실측 문제 시 후속.

## Blast Radius — 기존 테스트 시드 보강

리뷰 작성 API(POST /api/reviews)를 호출하는 테스트는 이제 (작성자, 음식) 스캔 이력이 선행돼야 한다. 대상 파일(리뷰 생성 헬퍼가 있는 곳에 `seedScan(memberId, foodId)` 삽입— 파일당 헬퍼 1곳이 원칙):

- `review/ReviewControllerTest.kt` (create 헬퍼)
- `review/ReviewListControllerTest.kt` · `review/GlobalReviewListControllerTest.kt` · `review/ReviewBlockFilterTest.kt`
- `food/FoodDetailReviewSectionTest.kt` · `food/FoodDetailRatingTest.kt`
- `bookmark/BookmarkControllerTest.kt` · `report/ReportControllerTest.kt`(API 로 리뷰 만드는 경로만 — SQL 직삽입은 무관)
- `scenario/ScenarioApiDriver.kt` (시나리오 드라이버)

시드 SQL 은 `FoodScannedListControllerTest` 의 현행 shape 재사용: `INSERT INTO scan_history (member_id, price, food_id, status, created_at, updated_at) VALUES (?, NULL, ?, 'ACTIVE', NOW(6), NOW(6))`.

## Complexity Tracking

위반 없음.
