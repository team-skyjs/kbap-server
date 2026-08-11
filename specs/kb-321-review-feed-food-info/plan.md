# Implementation Plan: 전체 리뷰 조회(무한 스크롤) 및 리뷰 응답 음식 정보 포함

**Branch**: `kb-321-review-feed-food-info` | **Date**: 2026-08-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-321-review-feed-food-info/spec.md`

## Summary

음식 지정 없이 서비스 전체 리뷰를 최신순으로 내리는 전체 피드 API `GET /api/v1/reviews/feed`(커서 기반, 기존 `Page`/`PAGE_SIZE=20` 재사용)를 추가하고, `ReviewResponse` 에 중첩 `food` 객체(음식 id·표시 이름·대표 이미지 URL)를 보강한다. 음식 이름은 언어별 번역(`Food.displayName(lang)`)이므로 세 목록 경로(피드·음식별·내 리뷰)에 `lang` 필수 파라미터를 추가한다(헌법 V). 신규 엔티티·스키마 변경 없음 — `ReviewJpaRepository` 쿼리 1개와 API 계층 확장이 전부다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Spring Boot 4.1)

**Primary Dependencies**: 기존 스택 그대로 — spring-web·validation·data-jpa·springdoc. 신규 의존성 없음

**Storage**: MySQL — **스키마 변경 없음**(신규 테이블·컬럼·Flyway 마이그레이션 없음). `review`·`food` 기존 테이블 조회만 추가

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + MySQL Testcontainers. repository 쿼리 테스트(`:common`) + MockMvc 통합 테스트(`:api`)

**Target Platform**: `:api` web bootJar (배치·인프라 무관)

**Project Type**: 기존 모듈러 모놀리스 내 기능 확장 (`:api` + `:common` review 도메인)

**Performance Goals**: 피드 1페이지(20건) 조회 시 쿼리 수 고정(리뷰 1 + 작성자 1 + 음식 1 + 좋아요 2) — 리뷰 건수 비례 N+1 금지

**Constraints**: 기존 응답 필드 제거·변경 금지(필드 추가만). 기존 커서 규칙(id desc, `nextCursor`) 유지

**Scale/Scope**: 신규 엔드포인트 1, 기존 엔드포인트 2 에 `lang` 추가, 응답 DTO 1 확장, repository 쿼리 1 추가

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 모든 task 를 실패 테스트 선행(Red→Green)으로 진행 — repository 쿼리·MockMvc 통합 테스트를 구현 전에 작성 |
| II. Bounded Contexts | ✅ | review→food 참조는 기존처럼 `foodId`(Long)·id 배치 조회. 응답 조립은 `com.kbap.api.review` 기능 패키지 소유. 도메인 허용 방향 맵 변경 없음(JPQL 서브쿼리는 import 미발생) |
| III. Dependency Direction | ✅ | `:api` → `:common` 방향만 사용. 신규 모듈·seam 없음 |
| IV. Persistence Ownership | ✅ | 쿼리는 `common.domain.review.ReviewJpaRepository` 에 추가(소유 도메인). JPA 연관관계 추가 없음, 트랜잭션 경계는 `ReviewService` 가 명시(`@Transactional(readOnly = true)`) |
| V. Language Policy | ✅ | `lang` **필수**(`@field:NotBlank`) + `LanguageCode.from`(미지원 코드→EN) + `LocalizedText.resolve`(번역 부재→ko) — KB-201 확립 패턴 그대로 |

**게이트 통과** — 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-321-review-feed-food-info/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── review-feed-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/review/
└── ReviewJpaRepository.kt          # findGlobalReviewPage 쿼리 추가

common/src/test/kotlin/com/kbap/common/domain/review/
└── ReviewJpaRepositoryTest.kt      # 전역 피드 쿼리 테스트 추가(차단·신고·삭제음식·커서)

api/src/main/kotlin/com/kbap/api/review/
├── ReviewController.kt             # GET /reviews/feed 추가, 기존 목록 2개에 lang 바인딩
├── ReviewApi.kt                    # swagger 문서 갱신
├── ReviewListRequest.kt            # lang 필드 추가(세 요청 DTO), FeedReviewListRequest 추가
├── ReviewResponse.kt               # 중첩 food 객체(ReviewFoodResponse) 추가
└── ReviewService.kt                # getFeedReviewPage 추가, toPage 에 음식 배치 조회 합류

api/src/test/kotlin/com/kbap/api/review/
├── ReviewFeedControllerTest.kt     # 신규 — 피드 통합 테스트
└── ReviewListControllerTest.kt     # 기존 — lang·food 응답 검증 보강
```

**Structure Decision**: 기존 `com.kbap.api.review` 기능 패키지와 `common.domain.review` 소유 구조를 그대로 확장한다. 신규 패키지·모듈 없음.

## Complexity Tracking

위반 없음 — 해당 없음.
