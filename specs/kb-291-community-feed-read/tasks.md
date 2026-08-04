# Tasks: 커뮤니티 피드 조회 + 글 상세 — 커서 페이징·게스트 게이트 (KB-291)

**Input**: Design documents from `/specs/kb-291-community-feed-read/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/feed-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 Kotest BehaviorSpec(given/when/then 한국어), 통합 테스트는 `@SpringBootTest` + MockMvc + MySQL Testcontainers.

**Organization**: 스토리별 단계 구성 — US1(회원 피드)만으로 MVP 성립. 스키마 변경 없음(마이그레이션 태스크 없음).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 태스크 의존 없음)
- **[Story]**: US1(회원 피드) / US2(게스트 게이트) / US3(글 상세) / US4(탈퇴 익명화)

## Path Conventions

모듈러 모놀리스 — `api/src/{main,test}/kotlin/com/kbap/api/`, `common/src/main/kotlin/com/kbap/common/`. 아래 경로는 워크트리 루트 기준.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 전 스토리가 공유하는 에러 코드·리포지토리 쿼리 기반

- [X] T001 [P] `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `COMMUNITY_LOGIN_REQUIRED("COMMUNITY-005", 401, "로그인이 필요합니다")` 추가 (`ErrorCodeStatusTest` 가 형식·유일성 자동 검증)
- [X] T002 [P] `common/src/main/kotlin/com/kbap/common/domain/community/PostingJpaRepository.kt` 에 신규 쿼리 2개 추가: `findFeedPage(cursor: Long?, pageable: Pageable): List<Posting>` (`@Query` — cursor null 이면 전체, 아니면 `id < :cursor`, id DESC) · `findIdsFrom(cursor: Long, pageable: Pageable): List<Long>` (`@Query` id 프로젝션 — `id >= :cursor` ASC, 게이트용 LIMIT 판정)

**Checkpoint**: `:common` 컴파일 통과 — 쿼리 동작 검증은 US1/US2 통합 테스트가 담당(Red 단계에서 함께 작성)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 게스트 GET 이 401 로 막히지 않도록 인증 필터 예외 확보 — 모든 스토리의 게스트·선택 인증 시나리오가 이것에 막힌다

- [X] T003 `api/src/test/kotlin/com/kbap/api/community/CommunityFeedControllerTest.kt` 신규 생성 — 필터 예외 Red 테스트 2건: `given("비로그인 게스트") when("GET /api/v1/community/posts?lang=en") then("401 이 아니라 200")`, `when("GET /api/v1/community/posts/1?lang=en") then("401 이 아니라 200/400 계열")`. 실행해 **Red 확인**(현재는 필터가 401 INVALID_ACCESS_TOKEN 반환)
- [X] T004 `api/src/main/kotlin/com/kbap/api/core/auth/JwtAuthenticationFilter.kt` 에 `shouldNotFilter` 오버라이드 추가 — 생성자에 예외 패턴 목록(기본 빈 목록) 주입, **GET + `^/api/v1/community/posts$`·`^/api/v1/community/posts/\d+$` 정확 일치**만 건너뜀. `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` 등록부에서 패턴 전달. 컨트롤러가 아직 없으므로 T003 은 404/400 까지 내려가면 통과로 본다(401 탈출이 목적)

**Checkpoint**: 게스트 GET 이 필터를 통과 — 이후 모든 스토리 착수 가능

---

## Phase 3: User Story 1 — 회원이 커뮤니티 피드를 훑어본다 (P1) 🎯 MVP

**Goal**: 최신순 커서 페이징 피드. 항목 = 작성자·본문·사진·음식 태그(id+요청 언어 이름)·카운트(0)·작성 시각

**Independent Test**: 글 여러 건 등록 후 회원 자격으로 첫 페이지 → nextCursor 로 다음 페이지 → 최신순·중복 없음·이어받기 확인

### Tests (Red 먼저 — 구현 전 실행해 실패 확인)

- [X] T005 [US1] `api/src/test/kotlin/com/kbap/api/community/CommunityFeedControllerTest.kt` 에 US1 시나리오 추가 — 회원 토큰으로: 커서 없이 최신 20건+nextCursor / nextCursor 이어받기(중복 없음) / 남은 글 < 20 이면 hasNext=false / 빈 피드 `items:[]` / 삭제 글 제외 / 사진·태그 없는 글 빈 배열 / foodTags 가 `{foodId, name}` (lang=en 이면 영어 이름, 번역 부재 시 ko 폴백) / lang 누락 400 / 잘못된 커서(`abc`·`-1`) 400 INVALID_CURSOR / likeCount·dislikeCount·commentCount = 0. 실행해 **Red 확인**

### Implementation (Green)

- [X] T006 [P] [US1] `api/src/main/kotlin/com/kbap/api/community/CommunityFeedItemResponse.kt` 신규 — `CommunityFeedItemResponse`(postId·author·content·imageUrls·foodTags·likeCount·dislikeCount·commentCount·createdAt) + `CommunityAuthorResponse`(memberId?·nickname·profileImageUrl?) + `CommunityFoodTagResponse`(foodId·name), swagger `@Schema` 포함 (data-model.md 표 그대로)
- [X] T007 [P] [US1] `api/src/main/kotlin/com/kbap/api/community/CommunityFeedRequest.kt` 신규 — `lang`(`@field:NotBlank`)·`cursor`(String?, `CursorParser.parse` 는 서비스/컨트롤러에서) — 기존 `HomeRequest`·`ReviewListRequest` 스타일
- [X] T008 [US1] `api/src/main/kotlin/com/kbap/api/community/CommunityService.kt` 에 조회 추가 — `@Transactional(readOnly = true) fun getFeedPage(viewerMemberId: Long?, cursor: Long?, lang: LanguageCode): Page<CommunityFeedItemResponse>`: `findFeedPage(cursor, PAGE_SIZE+1)` → hasNext/take 패턴(ReviewService.toPage 선례) → **단일 조립 함수** `assemble(postings, lang)`(회원 `findAllById` 일괄 + `foodService.getReadyFoodsByIds` 일괄 + 항목 매핑, 카운트 0 고정, `ImageUrls.resolve`). `PAGE_SIZE = 20` companion. 활성 회원 작성자는 실제 닉네임·프로필 이미지 URL
- [X] T009 [US1] `api/src/main/kotlin/com/kbap/api/community/CommunityController.kt` + `CommunityApi.kt` 에 `GET /community/posts` 추가 — `@AuthMemberIdOrNull memberId`, `@Valid @ModelAttribute CommunityFeedRequest`, `LanguageCode.from(request.lang)`, `ResponseEntity<BaseResponse<Page<CommunityFeedItemResponse>>>`. swagger 문서는 CommunityApi 인터페이스에만. T005 실행해 **Green 확인**

**Checkpoint**: 회원 피드 완전 동작 — MVP 배포 가능

---

## Phase 4: User Story 2 — 게스트가 피드를 맛보고 로그인 게이트를 만난다 (P1)

**Goal**: 게스트 1~2페이지 허용, 초과 커서는 COMMUNITY-005(401). 만료 토큰은 기존 AUTH 오류 유지

**Independent Test**: 비로그인으로 1·2페이지 성공, 3페이지 커서에서 COMMUNITY-005, 같은 커서를 회원 토큰으로 재요청하면 200

### Tests (Red 먼저)

- [X] T010 [US2] `CommunityFeedControllerTest.kt` 에 US2 시나리오 추가 — 게스트로: 1페이지 200(회원과 동일 형태) / 2페이지 200 / 3페이지 커서 401 + code=COMMUNITY-005 / 임의 깊은 커서(예: 1) 도 401 COMMUNITY-005 / 글이 40건 미만이면 끝까지 hasNext=false 정상 종료 / 같은 3페이지 커서 + 회원 토큰 200 / 만료·위조 토큰은 AUTH 오류(게스트 강등 아님). 실행해 **Red 확인**

### Implementation (Green)

- [X] T011 [US2] `CommunityService.getFeedPage` 에 게이트 삽입 — `viewerMemberId == null && cursor != null` 이면 `findIdsFrom(cursor, PageRequest.of(0, PAGE_SIZE + 1)).size > PAGE_SIZE` 일 때 `BusinessException(ErrorCode.COMMUNITY_LOGIN_REQUIRED)` (research.md R2 — LIMIT 21 프로젝션으로 최악 스캔 고정). T010 실행해 **Green 확인**

**Checkpoint**: 게스트 게이트 동작 — FE 로그인 게이트 연동 가능

---

## Phase 5: User Story 3 — 누구나 글 상세를 열어본다 (P2)

**Goal**: `GET /community/posts/{postId}` — 게스트 제한 없음, 피드 항목과 동일 형태

**Independent Test**: 글 1건 등록 후 회원·게스트 각각 상세 조회 → 동일 응답, 삭제·미존재 id 는 COMMUNITY-001

### Tests (Red 먼저)

- [X] T012 [US3] `CommunityFeedControllerTest.kt` 에 US3 시나리오 추가 — 회원 상세 200(본문·사진 전체·foodTags·카운트 0) / 게스트 상세 200(회원과 동일, 게이트 없음) / 삭제된 글 400 COMMUNITY-001 / 미존재 id 400 COMMUNITY-001 / lang 누락 400. 실행해 **Red 확인**

### Implementation (Green)

- [X] T013 [US3] `CommunityService` 에 `@Transactional(readOnly = true) fun getPosting(postId: Long, lang: LanguageCode): CommunityFeedItemResponse` 추가 — `findById` orElseThrow COMMUNITY_POSTING_NOT_FOUND(소프트 삭제는 `@SQLRestriction` 이 자동 제외) → US1 의 `assemble` 재사용(FR-010 단일 경로). `CommunityController`/`CommunityApi` 에 `GET /community/posts/{postId}`(`@AuthMemberIdOrNull`, lang 필수) 추가. T012 실행해 **Green 확인**

**Checkpoint**: 공유 링크 착지점 동작

---

## Phase 6: User Story 4 — 탈퇴한 사용자의 글이 익명으로 남는다 (P3)

**Goal**: 탈퇴 작성자 글 = 콘텐츠 유지 + `author = { memberId: null, nickname: "탈퇴한 사용자", profileImageUrl: null }`

**Independent Test**: 글 작성 회원을 withdraw 처리 후 피드·상세 조회 → 본문 유지·작성자만 익명 표기

### Tests (Red 먼저)

- [X] T014 [US4] `CommunityFeedControllerTest.kt` 에 US4 시나리오 추가 — 작성자 탈퇴 후: 피드에서 글 유지 + author.memberId=null + nickname="탈퇴한 사용자" + profileImageUrl=null / 상세도 동일 / 활성 회원 글은 실제 닉네임·프로필. 실행해 **Red 확인** (T008 구현이 이미 통과시킬 수 있음 — 그 경우 Red 없이 회귀 고정 테스트로 기록)

### Implementation (Green)

- [X] T015 [US4] `assemble` 의 익명화 분기 확정 — `memberRepository.findAllById` 결과에 없는 memberId(탈퇴 = status DELETED, `@SQLRestriction` 제외)는 `CommunityAuthorResponse(memberId = null, nickname = "탈퇴한 사용자", profileImageUrl = null)`. 문구 상수는 `CommunityService` companion `WITHDRAWN_AUTHOR_NICKNAME`. T014 실행해 **Green 확인**

**Checkpoint**: 전 스토리 수용 시나리오 충족

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T016 [P] `CommunityApi.kt` swagger 문서 정비 — 피드·상세 `@Operation`(게스트 게이트·익명화·커서 규약 설명), `@ApiResponses`(200/400/401 + COMMUNITY-005), 파라미터 문서. 인증 애너테이션 숨김은 OpenApiConfig 기존 설정으로 충분한지 확인
- [X] T017 ArchUnit·전체 회귀 — `./gradlew :api:test -Dkotest.tags="arch"` (컨트롤러 `/api/v` 규약·도메인 방향 맵) 후 `./gradlew build` 전체 통과 확인
- [ ] T018 quickstart.md 수동 시나리오 검증 — local 부팅 후 게스트 1·2·3페이지 curl, 회원 3페이지, 상세 게스트 조회 (quickstart.md 명령 그대로)

---

## Dependencies & Execution Order

```
Phase 1 (T001·T002 병렬)
   ↓
Phase 2 (T003 Red → T004 Green)   ← 게스트 401 차단 해소, 모든 스토리의 전제
   ↓
Phase 3 US1 (T005 Red → T006·T007 병렬 → T008 → T009 Green)   🎯 MVP
   ↓
Phase 4 US2 (T010 Red → T011 Green)   ← US1 의 getFeedPage 에 게이트 삽입
Phase 5 US3 (T012 Red → T013 Green)   ← US1 의 assemble 재사용 (US2 와 병렬 가능)
   ↓
Phase 6 US4 (T014 Red → T015 Green)   ← assemble 익명화 분기 (US2·US3 과 독립이나 파일 겹침 주의)
   ↓
Phase 7 (T016 병렬 · T017 → T018)
```

- **스토리 간 병렬**: US2(T010~T011)와 US3(T012~T013)은 서로 독립 — 단 둘 다 `CommunityService.kt`·`CommunityFeedControllerTest.kt` 를 수정하므로 같은 워킹카피에서는 순차 권장
- **파일 병렬 [P]**: T001/T002(다른 모듈), T006/T007(신규 파일 2개), T016(문서 전용)

## Implementation Strategy

**MVP**: Phase 1~3 (T001~T009) — 회원 피드만으로 FE 목 교체 시작 가능.

**증분 배포**: US2(게이트) → US3(상세) → US4(익명화) 순. 각 체크포인트마다 독립 테스트 기준 충족 후 다음 단계.

**주의**:
- 통합 테스트 시드: 회원·음식은 기존 테스트 픽스처 패턴(Testcontainers MySQL + Flyway 시드) 재사용. 음식 태그 테스트는 READY 상태 음식 필요
- `CommunityControllerTest`(KB-290 작성분)와 파일 분리 유지 — 읽기 테스트는 `CommunityFeedControllerTest`
- Kotlin 주석 금지 규약: 게이트 LIMIT 근거 같은 "코드로 표현 불가능한 제약"만 한 줄 주석 허용
