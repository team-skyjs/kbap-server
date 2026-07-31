# Implementation Plan: 사용자 차단 (Member Block)

**Branch**: `kb-131-member-block` | **Date**: 2026-08-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-131-member-block/spec.md`

## Summary

회원이 다른 회원을 단방향으로 차단·해제·목록 조회하고, 음식 리뷰 목록 조회 시 **조회 시점에** 조회자가 차단한 회원의 리뷰를 제외한다. 집계(평균 별점·리뷰 수)는 전역 값을 유지한다.

구현 골자: 차단은 **독립 block 컨텍스트** — `common.domain.block` 에 `MemberBlock` 엔티티(BaseEntity 소프트삭제)·리포지토리·`MemberBlockService`(자기 차단 금지, 소프트삭제 부활, 멱등)를 두고, 컨트롤러·응답 조립은 신규 기능 패키지 `com.kbap.api.block` 에 둔다. 대상 회원 검증을 위해 ArchUnit 허용 맵에 block→member 단방향을 추가한다. 리뷰 필터는 `com.kbap.api.review.ReviewService` 가 차단 id 목록을 받아 `ReviewJpaRepository` 쿼리에 `excludedMemberIds` 로 넘기는 조합(컨텍스트 간 조합은 api 기능 패키지 소관)으로 얹는다. Flyway 마이그레이션 1건(`member_block` 테이블, UNIQUE(blocker, blocked))을 추가한다.

Jira KB-131 본문의 설계 절(:core:member internal 캡슐화·:application:client)은 구 구조(KB-134) 기준이라 폐기하고, 현행 헌법 v7.0.0(ADR-0016·0017, KB-220 public 리포지토리) 구조로 재설계했다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway(+flyway-mysql)

**Storage**: MySQL — 신규 `member_block` 테이블 1개 (Flyway 마이그레이션, 스키마 owner=`:api`)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + JUnit 5 플랫폼, 통합 테스트는 MySQL Testcontainers(`MySqlContainerConfig`, Flyway on), MockMvc

**Target Platform**: `:api` web bootJar (배치·infra 무관 — `:common` 도메인 + `:api` 기능 패키지만 변경)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경 모듈: `:common`, `:api`

**Performance Goals**: 리뷰 목록 p95 기존 수준 유지 — 차단 필터는 조회자당 1회 id 목록 조회(개인당 차단 수 소수 전제) + `NOT IN` 조건 1개

**Constraints**: 소프트삭제 상시 필터(`@SQLRestriction`) 하에서 재차단 시 UNIQUE 위반 없이 DELETED 행 부활, 차단·해제 멱등, 집계 쿼리 무변경

**Scale/Scope**: 엔드포인트 신규 3종 + 기존 리뷰 목록 1종 수정, 신규 테이블 1개, 신규 ErrorCode 2개(BLOCK- 접두 신설), 신규 도메인 컨텍스트 1개(block)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 task 를 Red→Green→Refactor 로 진행(tasks 단계에서 테스트 선행 배치). 도메인 단위(자기 차단 금지)·Testcontainers(부활 시나리오)·MockMvc(차단·해제·목록·400·404·401·필터 적용) 테스트 계획 수립 |
| II. Bounded Contexts | PASS | `MemberBlock` 은 신규 **block 컨텍스트**(`common.domain.block`) 소유 — UGC 공통 노출 제외 규칙(FR-011)이라 member 프로필·랭킹과 관심사를 분리. `ModuleBoundaryTest` 허용 맵에 `"block" to setOf("member")` 단방향 1건 추가(대상 회원 존재 검증 — 맵 수정이 공인된 확장 경로, 순환 없음·member 는 block 을 모름). 리뷰 목록 + 차단 필터 조합은 `com.kbap.api.review`(도메인 맵 비대상)가 담당하고 review 도메인은 `Long` id 목록만 받는다 |
| III. Layered Dependency Direction | PASS | 변경 방향 전부 api→common. infra·port·batch 무관 |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리는 `common.domain.block`(모델은 `model/`)에 public. JPA 연관관계 없음(참조는 `Long` id), FK 는 Flyway 스키마가 강제. 차단 도메인 로직(자기 차단 금지·부활·멱등)은 도메인 서비스 `MemberBlockService` 소유, 트랜잭션 경계 명시(`@Transactional`). 상태 무시 조회는 native query(기존 선례: `FoodJpaRepository`·`ScanHistoryJpaRepository`) |
| V. Domain Content Language Policy | N/A | 음식 콘텐츠·표시 언어 무관(닉네임은 사용자 원문 그대로) |
| 추가 제약 | PASS | 외부 시스템 호출 없음(트랜잭션 내 외부 호출 이슈 없음), 엔티티를 응답으로 직접 노출하지 않음(`BlockedMemberResponse` DTO) |

**Post-Phase-1 재평가**: PASS — data-model·contracts 설계가 위 판정을 바꾸지 않음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-131-member-block/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── member-block-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V<생성시각 timestamp>__member_block_table.sql          # 신규 — member_block 테이블

common/src/main/kotlin/com/kbap/common/
├── core/error/ErrorCode.kt                                # 수정 — SELF_BLOCK_FORBIDDEN(BLOCK-001, 400)·BLOCK_TARGET_NOT_FOUND(BLOCK-002, 404)
└── domain/block/
    ├── model/MemberBlock.kt                               # 신규 — 엔티티(BaseEntity 상속, blockerMemberId·blockedMemberId)
    ├── MemberBlockJpaRepository.kt                        # 신규 — 차단 id 목록 JPQL + 상태 무시 native 조회
    └── MemberBlockService.kt                              # 신규 — 도메인 서비스: block(부활·멱등)·unblock(멱등)·getBlockedMemberIds

api/src/main/kotlin/com/kbap/api/
├── block/
│   ├── MemberBlockApi.kt                                  # 신규 — swagger 문서 인터페이스
│   ├── MemberBlockController.kt                           # 신규 — POST/DELETE/GET /api/v1/members/me/blocks + 목록 응답 조립
│   ├── MemberBlockRequest.kt                              # 신규 — 차단 등록 body(memberId)
│   └── BlockedMemberResponse.kt                           # 신규 — memberId·nickname·profileImageUrl
└── review/
    ├── ReviewService.kt                                   # 수정 — getFoodReviewPage 에 viewerMemberId 추가, 차단 id 제외 전달
    ├── ReviewController.kt                                # 수정 — listFoodReviews 에 @AuthMemberId 추가
    └── ReviewApi.kt                                       # 수정 — 인터페이스 파라미터 동기화(타입만)

common/src/main/kotlin/com/kbap/common/domain/review/
└── ReviewJpaRepository.kt                                 # 수정 — findFoodReviewPage 에 excludedMemberIds 조건 추가

api/src/test/kotlin/com/kbap/api/architecture/
└── ModuleBoundaryTest.kt                                  # 수정 — allowedDomainDeps 에 "block" to setOf("member") 추가

테스트 (미러 구조):
common/src/test/kotlin/com/kbap/common/domain/block/       # MemberBlockService 단위·Testcontainers 부활 시나리오
api/src/test/kotlin/com/kbap/api/block/                    # MockMvc 차단·해제·목록·400·404·401
api/src/test/kotlin/com/kbap/api/review/                   # MockMvc 차단 필터 적용·해제 후 재노출·집계 불변
```

**Structure Decision**: 차단은 독립 **block 컨텍스트** — 영속·도메인 로직은 `common.domain.block`, HTTP 경계·응답 조립은 신규 기능 패키지 `com.kbap.api.block` 에 둔다(파일 수가 적어 `dto/` 하위 패키지 미생성 — CLAUDE.md 컨벤션). member 소속 대안은 기각 — UGC 공통 규칙(FR-011)이라 소비자가 리뷰·커뮤니티 쪽이고, member 패키지 비대화를 피한다(research R6). 인증은 기존 `JwtAuthenticationFilter` 등록 패턴 `/api/v1/members/*` 가 `/members/me/blocks` 하위 경로를 이미 커버하므로 `WebConfig` 수정이 없다.

## Complexity Tracking

위반 없음 — 해당 없음.
