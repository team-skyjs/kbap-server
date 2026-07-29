# Implementation Plan: 리뷰 CRUD — 별점·본문·사진(≤3) + 전체/같은 국적 평점

**Branch**: `kb-128-review-crud` | **Date**: 2026-07-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-128-review-crud/spec.md` (Jira KB-128, 2026-07-29 기획 확정)

## Summary

리뷰 컨텍스트를 신설한다 — 별점(1~5 필수)·본문(≤1000자)·사진(≤3장, JSON 컬럼) 리뷰의 CRUD, 음식별/내 리뷰 keyset 목록, 음식 상세의 전체·같은 국적 평점(작성 시점 국적 스냅샷 기준), 랭킹 카운트(review_count·unique_reviewed_food_count) 원자 증감 연동. **4개 PR 로 분할 구현한다**: ① persistence(스키마+엔티티+리포지토리) → ② write(작성/수정/삭제+랭킹) → ③ lists(목록 2종) ∥ ④ rating(음식 상세 확장). ③·④는 ①에만 의존하므로 병렬 진행 가능.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway(+mysql)

**Storage**: MySQL — 신규 `review` 테이블 1건(Flyway). `member.review_count`·`unique_reviewed_food_count` 컬럼은 **기존재**(init_schema) — 회원 측 마이그레이션 불필요

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + MySQL Testcontainers. 도메인 단위(:common)·영속(:common, Hibernate create)·MockMvc 통합(:api, Flyway+`ddl-auto=validate`)

**Target Platform**: `:api` web bootJar (배치 무관)

**Project Type**: 모듈러 모놀리스 — 영속은 `:common`(`common.domain.review`), API 조합은 `:api`(`com.kbap.api.review`)

**Performance Goals**: 목록 keyset 20건/페이지(offset 금지), 평점은 AVG 집계 쿼리(비정규화 없음) — 인덱스 3종으로 커버

**Constraints**: JPA 연관관계 금지(memberId·foodId = Long 값, FK 는 Flyway), 소프트삭제(`@SQLRestriction` 자동), 랭킹 카운트는 읽고-더해-쓰기 금지(JPQL 원자 증감), 트랜잭션은 서비스 public 메서드 명시 선언

**Scale/Scope**: 엔드포인트 5종 + 음식 상세 응답 확장 1건, 엔티티 1·리포지토리 1, ErrorCode `REVIEW-` 접두 신설

## Constitution Check

*GATE: v7.0.0 기준 — Phase 1 설계 후 재점검 완료, 전 게이트 PASS.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 각 PR 을 Red→Green→Refactor 로 진행(tasks.md 가 테스트 task 선행 배치). 영속·도메인 불변·MockMvc 전 구간 테스트 |
| II. Bounded Contexts | PASS | 신규 `common.domain.review` 컨텍스트 — 타 컨텍스트 참조는 id 값(`memberId`·`foodId`)과 국적 코드 스냅샷(String)뿐. `ModuleBoundaryTest` 허용 맵에 `"review" to emptySet()` 추가(도메인 간 의존 0). 평점↔음식 상세 합성은 컨트롤러가 수행(bookmark 선례) |
| III. Dependency Direction | PASS | 신규 모듈 없음. 영속은 `:common`, 조합은 `com.kbap.api.review`. seam 불필요(외부 시스템 호출 없음 — 이미지 검증은 기존 `ImageUploadService` 재사용) |
| IV. Persistence Ownership | PASS | `Review` 엔티티=도메인 모델(불변 검증 메서드 내장)·public 리포지토리·JPA 연관 없음·FK 는 Flyway·명시 `@Transactional` |
| V. Language Policy | PASS(해당 없음) | 리뷰 본문은 사용자 생성 콘텐츠 — 번역 대상 아님. 음식 콘텐츠 언어 정책과 무관. 목록 응답에 `lang` 파라미터 불요 |

## Project Structure

### Documentation (this feature)

```text
specs/kb-128-review-crud/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 선례 조사·결정 기록
├── data-model.md        # Phase 1 — Review 엔티티·스키마·집계
├── quickstart.md        # Phase 1 — 검증 절차
├── contracts/
│   └── review-api.md    # Phase 1 — 엔드포인트 5종 + 음식 상세 확장 계약
└── tasks.md             # Phase 2 (/speckit-tasks — PR 단위 그룹핑)
```

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V2026.07.29.HH.mm.ss__food_review_table.sql        # PR1 — food_review 테이블+인덱스 3종+FK 2종

common/src/main/kotlin/com/kbap/common/domain/review/
├── model/Review.kt                                # PR1 — BaseEntity 상속, 불변(별점·본문·사진) 내장
└── ReviewJpaRepository.kt                         # PR1 — keyset 2종·AVG 집계·member-food count

common/src/main/kotlin/com/kbap/common/domain/member/
├── MemberJpaRepository.kt                         # PR2 — increase/decreaseReviewCounts JPQL 추가
└── MemberService.kt                               # PR2 — 리뷰 카운트 증감 메서드 추가

common/src/main/kotlin/com/kbap/common/core/error/
└── ErrorCode.kt                                   # PR2 — REVIEW-001~ 신설

api/src/main/kotlin/com/kbap/api/review/
├── ReviewApi.kt                                   # PR2~3 — swagger 인터페이스
├── ReviewController.kt                            # PR2~3 — @RequestMapping(ApiPaths.V1), 5 엔드포인트
├── ReviewService.kt                               # PR2~4 — 작성/수정/삭제·목록·평점 집계
├── ReviewCreateRequest.kt / ReviewUpdateRequest.kt# PR2
├── ReviewResponse.kt / ReviewListRequest.kt / ReviewPage.kt  # PR2~3
api/src/main/kotlin/com/kbap/api/image/
└── (UploadedImageRepository) findByPathIn 추가     # PR2 — 사진 ≤3장 일괄 소유 검증

api/src/main/kotlin/com/kbap/api/food/
├── FoodController.kt                              # PR4 — ReviewService 평점 합성
└── FoodDetailResponse.kt                          # PR4 — averageRating·reviewCount·sameCountryAverageRating

api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt  # PR1 — 허용 맵 "review" 추가
api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt      # PR2~3
common/src/test/kotlin/com/kbap/common/domain/review/                # PR1 — 단위·영속 테스트
```

**Structure Decision**: 기존 컨벤션 그대로 — 영속·엔티티는 `:common` 의 `common.domain.review`, API 전용 조합(리뷰는 api 만 소비)은 `com.kbap.api.review` 기능 패키지. 도메인 서비스는 common 에 두지 않는다(web·batch 공유 없음). 컨트롤러는 `ReviewController` 하나(`@RequestMapping(ApiPaths.V1)` + 메서드 레벨 `/reviews`·`/foods/{foodId}/reviews`·`/members/me/reviews`)로 리소스 경로가 갈리는 5 엔드포인트를 담는다.

## PR 분할 계획 (구현 단위)

| PR | 브랜치 | 범위 | 의존 | DoD |
|----|--------|------|------|-----|
| 1 | `kb-128-review-persistence` | Flyway `review` 테이블(인덱스 3·FK 2) · `Review` 엔티티(불변 3종) · `ReviewJpaRepository`(keyset 2·AVG 2·count) · `ModuleBoundaryTest` 맵 · 도메인 단위+영속 테스트 | develop | 1, 2 |
| 2 | `kb-128-review-write` | POST/PATCH/DELETE `/reviews` · `REVIEW-` ErrorCode · `findByPathIn` 사진 소유 검증 · 국적 스냅샷 · 랭킹 카운트 원자 증감(`MemberService`) · MockMvc 테스트 | PR1 | 3, 6 |
| 3 | `kb-128-review-lists` | GET `/foods/{foodId}/reviews`(keyset+국적 필터) · GET `/members/me/reviews` · MockMvc 테스트 | PR1 (PR2 와 독립) | 4 |
| 4 | `kb-128-review-rating` | 음식 상세 응답 확장 — averageRating(소수1)·reviewCount·sameCountryAverageRating(null 규칙) · MockMvc 테스트 | PR1 (PR2·3 과 독립) | 5 |

- 진행 순서: PR1 머지 → PR2 진행, PR3·PR4 는 PR1 머지 후 병렬 가능. PR1 미머지 상태로 이어가면 스택(base=이전 브랜치).
- DoD 7(테스트)·8(swagger) 은 각 PR 에 해당 분량 포함.
- 커밋·PR 은 이 워크트리에서 브랜치를 끊어 base=develop draft PR(`open-draft-pr-to-develop` 규약).

## Complexity Tracking

위반 없음 — 신규 모듈·신규 추상화·비정규화 없이 기존 선례(keyset·JSON 컬럼·JPQL 증감·컨트롤러 합성)만 재사용한다.
