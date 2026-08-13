# Implementation Plan: 리뷰 좋아요

**Branch**: `kb-271-review-like` | **Date**: 2026-08-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-271-review-like/spec.md`

## Summary

회원이 리뷰에 좋아요를 등록/취소하고(멱등), 리뷰 목록 응답에 좋아요 수와 조회 회원의 좋아요 여부를 포함한다. `member_block`(KB-131) 선례를 그대로 따른다: `review_like` 테이블에 `(review_id, member_id)` 유니크 제약 + 소프트삭제(취소=DELETED) + 재등록은 native `INSERT ... ON DUPLICATE KEY UPDATE` 부활 — 동시 중복 요청도 DB 제약이 원자적으로 막는다. 신규 도메인 서비스 없이 기존 `api`의 `ReviewService` 에 좋아요 메서드를 얹고, `ReviewResponse` 를 `likeCount`·`likedByMe` 로 확장한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi

**Storage**: MySQL (Flyway 마이그레이션, 스키마 owner = `:api`) — 신규 테이블 `review_like`

**Testing**: Kotest BehaviorSpec (given/when/then 한국어) + JUnit 5 platform, `@SpringBootTest` + MySQL Testcontainers(`MySqlContainerConfig`), MockMvc

**Target Platform**: Linux 서버 (api bootJar — 배치는 이 기능과 무관)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 변경 모듈은 `:common`(엔티티·리포지토리)·`:api`(서비스·컨트롤러·DTO·Flyway)

**Performance Goals**: 리뷰 목록(페이지 20건)의 좋아요 수·여부는 페이지당 추가 쿼리 2개(집계 1 + 내 좋아요 1)로 배치 로드 — 리뷰별 N+1 금지

**Constraints**: 동시 중복 좋아요에도 (review_id, member_id) 조합당 1행 (DB 유니크 제약이 강제 — 격리수준 조정·락 금지)

**Scale/Scope**: 신규 엔티티 1·리포지토리 1·마이그레이션 1·엔드포인트 2·응답 필드 2 — 소규모

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 모든 task 를 Red→Green→Refactor 로 진행. 엔티티/리포지토리/서비스/컨트롤러 테스트를 구현보다 먼저 작성 |
| II. Bounded Contexts | ✅ PASS | `ReviewLike` 는 review 컨텍스트 소유 — `com.kbap.common.domain.review.model`. member 참조는 `memberId: Long` 값. 도메인 간 신규 의존 없음(ArchUnit 허용 맵 변경 불필요) |
| III. Layered Dependency Direction | ✅ PASS | `:api` → `:common` 방향만 사용. 신규 모듈·seam 없음 |
| IV. Persistence Ownership | ✅ PASS | 엔티티·리포지토리는 `common.domain.review` 에 public. JPA 연관관계 없음(id 값 참조). FK·유니크는 Flyway 스키마가 강제. 트랜잭션 경계는 `ReviewService` 가 명시 선언 |
| V. Domain Content Language Policy | ✅ PASS (N/A) | 언어 콘텐츠 없음 — 좋아요는 수·여부만 |

**Post-Phase-1 재평가**: 설계 산출물(data-model·contracts) 확인 후에도 위반 없음 — 신규 추상화·창구 서비스·연관관계 미도입.

## Project Structure

### Documentation (this feature)

```text
specs/kb-271-review-like/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── review-like-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/review/
├── model/ReviewLike.kt                  # 신규 — BaseEntity 상속, reviewId·memberId
└── ReviewLikeJpaRepository.kt           # 신규 — upsertActive(native)·조회·배치 집계

common/src/test/kotlin/com/kbap/common/domain/review/
└── ReviewLikeJpaRepositoryTest.kt       # 신규 — Testcontainers 통합(유니크·부활·집계)

api/src/main/kotlin/com/kbap/api/review/
├── ReviewService.kt                     # 수정 — likeReview·unlikeReview + 목록 좋아요 enrich
├── ReviewController.kt                  # 수정 — POST/DELETE /reviews/{reviewId}/like
├── ReviewApi.kt                         # 수정 — swagger 문서 (인터페이스)
└── ReviewResponse.kt                    # 수정 — likeCount·likedByMe 추가

api/src/main/resources/db/migration/
└── V<timestamp>__review_like_table.sql  # 신규 — 파일 생성 시점 로컬 시각으로 명명

api/src/test/kotlin/com/kbap/api/review/
├── ReviewLikeControllerTest.kt          # 신규 — 등록/취소/멱등/404 MockMvc
└── ReviewListControllerTest.kt          # 수정 — 목록 응답 likeCount·likedByMe 검증 추가
```

**Structure Decision**: 기존 review 컨텍스트(`common.domain.review`)와 api 기능 패키지(`com.kbap.api.review`)를 그대로 확장한다. 신규 패키지·모듈·도메인 서비스를 만들지 않는다 — 좋아요는 api 전용 유스케이스이므로 기존 `ReviewService`(api)가 조합을 소유하고(북마크 `BookmarkService` 선례), 영속은 헌법 IV 에 따라 `:common` 이 소유한다.

## Complexity Tracking

위반 없음 — 해당 없음.
