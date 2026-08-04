# Implementation Plan: 커뮤니티 댓글/대댓글 — 1depth·등록순 커서

**Branch**: `kb-292-community-comments` | **Date**: 2026-08-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-292-community-comments/spec.md`

## Summary

커뮤니티 글에 텍스트 댓글/대댓글(1depth 고정)을 붙인다. 작성·수정·삭제는 회원 전용(본인만 수정·삭제), 목록은 회원 전용 등록순(오래된 순) 커서 페이징. 댓글 통삭제(하위 대댓글 일괄 소프트 삭제)·대댓글 단독 삭제, 탈퇴 작성자 익명화, 피드·상세의 `commentCount` 실값 배선까지가 범위다. @멘션은 본문 텍스트로만 취급한다(구조화 저장·알림 없음).

기술 접근: KB-290/291 이 깔아둔 community 도메인 위에 `Comment` 엔티티 하나를 추가한다(자기참조는 `parentId: Long?` id 값). 목록은 피드와 동일한 패턴 — `CursorParser` + `Page<T>` + size+1 hasNext — 을 top-level 댓글에 적용하고, 페이지에 실린 댓글들의 대댓글은 한 방에 로드해 중첩 응답한다. 통삭제는 삭제 시점 bulk soft-delete 로 처리해 모든 조회·카운트가 `@SQLRestriction(ACTIVE)` 하나로 끝나게 한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway

**Storage**: MySQL — 신규 `community_comment` 테이블(Flyway 마이그레이션, owner=api)

**Testing**: Kotest BehaviorSpec + JUnit 5 플랫폼, 통합은 `@SpringBootTest` + MySQL Testcontainers(`MySqlContainerConfig`) + MockMvc

**Target Platform**: Linux 서버(`:api` bootJar) — 배치 무관

**Project Type**: 웹 서비스(모듈러 모놀리스 — `:common` 도메인 + `:api` 기능 패키지)

**Performance Goals**: 목록 1페이지(20건+대댓글) 쿼리 3회 이내(top-level 페이지·대댓글 일괄·작성자 일괄), 피드 카운트는 페이지당 group-count 1회 추가

**Constraints**: 도메인 간 신규 의존 없음(`community` 허용 맵 `emptySet()` 유지 — member·food 참조는 id 값/API 계층 조립), JPA 연관관계 금지, 명시적 `@Transactional`

**Scale/Scope**: 엔드포인트 4개(작성·수정·삭제·목록) + 기존 피드/상세 카운트 배선, 엔티티 1·마이그레이션 1

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | tasks 단계에서 도메인 단위(CommentTest)·통합(MockMvc+Testcontainers) 테스트를 구현보다 먼저 작성(Red 확인) — tdd-harness 사이클로 진행 |
| II. Bounded Contexts | PASS | `Comment` 는 기존 `common.domain.community` 컨텍스트 소속. member·posting 참조는 id 값(`Long`)뿐 — `ModuleBoundaryTest` 의 `"community" to emptySet()` 허용 맵 변경 없음. 작성자 조립은 `com.kbap.api.community`(도메인 맵 비대상)에서 수행 |
| III. Layered Dependency Direction | PASS | 신규 코드는 `:common`(엔티티·리포지토리)과 `:api`(컨트롤러·서비스·DTO)에만 — 의존 방향 api→common 유지, seam·infra 무관 |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리는 `common.domain.community` 에 public. JPA 연관 없음(자기참조도 `parentId: Long?`). FK 는 Flyway 가 강제. 기존 `CommunityService` 가 리포지토리 직접 사용 + 명시적 `@Transactional`(KB-290 선례 그대로) |
| V. Language Policy | N/A | 댓글은 사용자 생성 콘텐츠 — 음식 콘텐츠 번역 정책 비대상. 댓글 목록 API 에 `lang` 불필요(음식 태그·번역 필드 없음) |

**Post-design re-check (Phase 1 완료 후)**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-292-community-comments/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── comments-api.md  # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/community/
├── model/
│   ├── Posting.kt                        # 기존
│   └── Comment.kt                        # 신규 — 엔티티(=도메인 모델), parentId 자기참조 id 값
├── PostingJpaRepository.kt               # 기존
└── CommentJpaRepository.kt               # 신규 — 커서 페이지·대댓글 일괄·post별 group-count·bulk soft-delete

common/src/test/kotlin/com/kbap/common/domain/community/model/
└── CommentTest.kt                        # 신규 — 도메인 단위 테스트

api/src/main/kotlin/com/kbap/api/community/
├── CommunityService.kt                   # 수정 — 댓글 작성/수정/삭제/목록 유스케이스 추가 + assemble 의 commentCount=0 → 실카운트 배선
├── CommunityApi.kt                       # 수정 — 댓글 엔드포인트 4개 swagger 문서 추가
├── CommunityController.kt                # 수정 — 댓글 매핑·바인딩·인증 추가
├── CommentCreateRequest.kt               # 신규 — 작성/수정 요청 DTO
├── CommentItemResponse.kt                # 신규 — 목록 항목(대댓글 중첩)·작성자 표시
└── CommentResponse.kt                    # 신규 — 작성/수정 결과

api/src/main/resources/db/migration/
└── V2026.MM.dd.HH.mm.ss__community_comment_table.sql   # 신규 — 생성 시각으로 명명

api/src/test/kotlin/com/kbap/api/community/
├── CommentControllerTest.kt              # 신규 — 작성/수정/삭제 통합
├── CommentReadControllerTest.kt          # 신규 — 목록·커서·익명화·게스트 거부 통합
└── PostingReadControllerTest.kt          # 수정 — commentCount 실값 검증 추가

common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt   # 수정 — COMMUNITY-006·007 추가
```

**Structure Decision**: KB-290/291 이 확립한 community 기능 구조를 그대로 따른다 — 영속은 `common.domain.community`, 유스케이스·HTTP 경계는 `com.kbap.api.community` 평탄 기능 패키지. 신규 모듈·패키지 없음.

## Complexity Tracking

위반 없음 — 해당 없음.
