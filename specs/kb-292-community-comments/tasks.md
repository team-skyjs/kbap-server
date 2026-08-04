# Tasks: 커뮤니티 댓글/대댓글 — 1depth·등록순 커서

**Input**: Design documents from `specs/kb-292-community-comments/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/comments-api.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 I) — 각 스토리의 테스트를 구현보다 먼저 작성하고 Red 를 확인한 뒤 Green 으로 간다.

**Organization**: 유저 스토리 단위 phase. 모든 경로는 repo 루트 기준.

## Phase 1: Setup

이미 존재하는 프로젝트 위 증분 — 별도 셋업 태스크 없음.

---

## Phase 2: Foundational (모든 스토리의 전제)

**Purpose**: 스키마·엔티티·리포지토리·오류 코드 — US1~3 전부가 딛는 토대.

- [ ] T001 Flyway 마이그레이션 `api/src/main/resources/db/migration/Vyyyy.MM.dd.HH.mm.ss__community_comment_table.sql` 생성 — data-model.md 의 DDL 그대로(FK 3·인덱스 2, ON DELETE 없음). 버전은 **파일 생성 시점 로컬 시각**으로 채번
- [ ] T002 [P] `common/src/test/kotlin/com/kbap/common/domain/community/model/CommentTest.kt` 도메인 단위 테스트 작성(BehaviorSpec, 한국어 given/when/then) — 공백 본문 거부·2000자 경계(2000 허용/2001 거부)·`update` 재검증+`editedAt` 갱신·`isOwnedBy`·`isReply` 판정. **컴파일 실패(Red) 확인**
- [ ] T003 `common/src/main/kotlin/com/kbap/common/domain/community/model/Comment.kt` 엔티티 구현(Posting 미러링 — BaseEntity 상속·`MAX_CONTENT_LENGTH=2000`·JPA 연관 없음·use-site 타깃 없는 애너테이션) → `./gradlew :common:test --tests "*.CommentTest"` **Green 확인**
- [ ] T004 `common/src/main/kotlin/com/kbap/common/domain/community/CommentJpaRepository.kt` 생성 — `findTopLevelPage(postId, cursor, pageable)`(id asc)·`findByParentIdInOrderByIdAsc`·`countByPostIds`(group-count projection)·`@Modifying softDeleteReplies(parentId)` 4메서드(data-model.md 표 준수, status 수동 조건 금지)
- [ ] T005 [P] `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `COMMUNITY_COMMENT_NOT_FOUND("COMMUNITY-006", 400)`·`COMMUNITY_COMMENT_FORBIDDEN("COMMUNITY-007", 403)` 추가(메시지는 contracts 표)

**Checkpoint**: `./gradlew :common:build` 통과 — 이후 모든 스토리 착수 가능.

---

## Phase 3: User Story 1 — 회원이 댓글과 대댓글을 작성한다 (P1) 🎯 MVP

**Goal**: `POST /api/v1/community/posts/{postId}/comments` — 최상위/답글 작성, 1depth 서버 정규화, 회원 전용.

**Independent Test**: 댓글→대댓글→대댓글에 답글 3건을 등록해 마지막 건의 `parentCommentId` 가 최상위 댓글로 정규화되는지 응답으로 확인.

- [ ] T006 [US1] `api/src/test/kotlin/com/kbap/api/community/CommentControllerTest.kt` 작성 통합 테스트(BehaviorSpec + SpringExtension + MockMvc + Testcontainers) — 최상위 작성 성공·답글 작성 성공·**대댓글에 답글 → parentCommentId 정규화**·빈 본문/2001자 400·게스트 401·없는/삭제된 글 `COMMUNITY-001`·없는/다른 글 소속 parent `COMMUNITY-006`. **Red 확인**(`./gradlew :api:test --tests "*.CommentControllerTest"`)
- [ ] T007 [P] [US1] `api/src/main/kotlin/com/kbap/api/community/CommentCreateRequest.kt` 생성 — `content`(`@NotBlank`+`@Size(max=Comment.MAX_CONTENT_LENGTH)`)·`parentCommentId: Long?`, `CommentUpdateRequest`(content만) 동파일. swagger `@Schema` 포함(Posting 요청 DTO 스타일)
- [ ] T008 [P] [US1] `api/src/main/kotlin/com/kbap/api/community/CommentResponse.kt` 생성 — commentId·postId·parentCommentId·content·createdAt·editedAt + `from(Comment)` 팩토리
- [ ] T009 [US1] `api/src/main/kotlin/com/kbap/api/community/CommunityService.kt` 에 `createComment(memberId, postId, content, parentCommentId)` 추가 — 글 존재 검증(탈퇴 작성자 글 포함 `COMMUNITY-001`, `getPosting` 의 exists 검사 재사용)·parent 조회(`COMMUNITY-006`, 다른 글 소속 거부)·**최상위 부모로 정규화**(`parent.parentId ?: parent.id`)·저장. 명시적 `@Transactional`
- [ ] T010 [US1] `CommunityApi.kt` 에 swagger 문서(작성), `CommunityController.kt` 에 `POST /community/posts/{postId}/comments` 매핑 추가(Spring 애너테이션은 컨트롤러에만) → T006 **Green 확인** 후 리팩터

**Checkpoint**: 댓글 작성 API 단독 동작 — MVP.

---

## Phase 4: User Story 2 — 회원이 댓글 목록을 등록순으로 조회한다 (P2)

**Goal**: `GET /api/v1/community/posts/{postId}/comments?cursor=` — 등록순 커서(20건, size+1), 대댓글 중첩, 삭제분 제외, 탈퇴 익명화, 회원 전용.

**Independent Test**: 댓글 25건+대댓글을 심고 커서 2페이지로 나눠 조회 — 등록순·중복/누락 없음·익명화 확인.

- [ ] T011 [US2] `api/src/test/kotlin/com/kbap/api/community/CommentReadControllerTest.kt` 목록 통합 테스트 작성 — 등록순 정렬(top-level·replies 각각)·커서 페이징(21건 → hasNext/nextCursor, 2페이지 합집합=전체·교집합=∅)·대댓글 중첩·**탈퇴 작성자 익명화(memberId null·"탈퇴한 사용자")**·게스트 401·없는 글 `COMMUNITY-001`·커서 형식 오류 `INVALID_CURSOR`. **Red 확인**
- [ ] T012 [P] [US2] `api/src/main/kotlin/com/kbap/api/community/CommentItemResponse.kt` 생성 — commentId·author(`CommentAuthorResponse`: memberId nullable·nickname·profileImageUrl)·content·createdAt·`replies: List<CommentReplyResponse>`(동파일, replies 필드 제외 동일 구조). editedAt 미노출
- [ ] T013 [US2] `CommunityService.kt` 에 `getCommentPage(memberId, postId, cursor)` 추가 — 글 존재 검증 → `findTopLevelPage`(PAGE_SIZE+1) → `findByParentIdInOrderByIdAsc` 일괄 로드 → 작성자 일괄 lookup(miss = `{memberId: null, nickname: "탈퇴한 사용자", profileImageUrl: null}`) → `Page<CommentItemResponse>` 조립. `@Transactional(readOnly = true)`
- [ ] T014 [US2] `CommunityApi.kt` 문서 + `CommunityController.kt` 에 `GET /community/posts/{postId}/comments` 매핑(`@AuthMemberId`·`CursorParser`) → T011 **Green 확인** 후 리팩터

**Checkpoint**: 작성+목록으로 댓글 스레드 왕복 완성.

---

## Phase 5: User Story 3 — 작성자가 자기 댓글을 수정·삭제한다 (P3)

**Goal**: `PUT·DELETE /api/v1/community/comments/{commentId}` — 본인만, 통삭제/단독 삭제, 피드·상세 `commentCount` 실값화.

**Independent Test**: 대댓글 2건 달린 댓글을 삭제 → 목록에서 3건 모두 소실 + `commentCount` 3 감소. 대댓글 단독 삭제 → 해당 건만 소실.

- [ ] T015 [US3] `CommentControllerTest.kt` 에 수정·삭제 시나리오 추가 — 본문 수정+`editedAt` 갱신·타인 댓글 403 `COMMUNITY-007`·**통삭제(댓글 삭제 → 대댓글까지 목록 소실)**·대댓글 단독 삭제·이미 삭제된 댓글 재수정/재삭제 400 `COMMUNITY-006`. **Red 확인**
- [ ] T016 [P] [US3] `api/src/test/kotlin/com/kbap/api/community/PostingReadControllerTest.kt` 에 commentCount 검증 추가 — 댓글+대댓글 등록 후 피드·상세 `commentCount` 실값, 통삭제 후 감소 반영. **Red 확인**
- [ ] T017 [US3] `CommunityService.kt` 에 `updateComment(memberId, commentId, content)`·`deleteComment(memberId, commentId)` 추가 — `getMyComment`(없음 `COMMUNITY-006`/타인 `COMMUNITY-007`), 삭제는 `isReply` 분기: 최상위면 `delete()` + `softDeleteReplies(id)`, 대댓글이면 `delete()` 만. 명시적 `@Transactional`
- [ ] T018 [US3] `CommunityService.assemble` 의 `commentCount = 0` 을 `countByPostIds` 배선으로 교체(페이지당 1쿼리) + `PostingItemResponse.commentCount` swagger 설명 갱신
- [ ] T019 [US3] `CommunityApi.kt` 문서 + `CommunityController.kt` 에 `PUT`·`DELETE /community/comments/{commentId}` 매핑 → T015·T016 **Green 확인** 후 리팩터

**Checkpoint**: 스펙 전 스토리 + DoD 5항목 충족.

---

## Phase 6: Polish & Cross-Cutting

- [ ] T020 전체 검증 `./gradlew build` — ArchUnit(`ModuleBoundaryTest`)·`ErrorCodeStatusTest`·엔티티↔스키마 `ddl-auto=validate` 포함 전 모듈 통과 확인
- [ ] T021 [P] quickstart.md 시나리오 수동 확인(로컬 bootRun + Swagger) — 1depth 정규화·통삭제·commentCount 눈검증

---

## Dependencies & Execution Order

```text
Phase 2 (Foundational): T001 → (T002 → T003) → T004, T005[P]
    ↓
Phase 3 (US1): T006(Red) → T007[P]·T008[P] → T009 → T010(Green)
    ↓
Phase 4 (US2): T011(Red) → T012[P] → T013 → T014(Green)   # T004 의 목록 쿼리 사용
    ↓
Phase 5 (US3): T015(Red)·T016[P](Red) → T017 → T018 → T019(Green)
    ↓
Phase 6: T020 → T021
```

- US2·US3 는 US1 의 작성 API 를 테스트 픽스처(댓글 심기)로 쓰므로 순차 진행이 자연스럽다. 독립 검증은 각 스토리의 Independent Test 기준으로 가능.
- [P] 태스크는 서로 다른 파일·의존 없음 — 동시 진행 가능(T002∥T005, T007∥T008, T015∥T016 등).

## Implementation Strategy

- **MVP = Phase 2 + Phase 3(US1)**: 작성 API 만으로 배포 가능한 증분.
- 각 phase 끝 checkpoint 에서 커밋(논리 단위 커밋 규율). Red 커밋은 만들지 않고 Red→Green 을 한 커밋으로 묶는다.
- tdd-harness-orchestrator 로 실행 시: test-writer(T002·T006·T011·T015·T016) → implementer(짝 구현) → code-reviewer∥database-expert(T001·T004 는 DB 리뷰 대상).
