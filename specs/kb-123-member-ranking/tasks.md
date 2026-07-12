---

description: "Task list for KB-123 회원 랭킹 산정 및 조회"
---

# Tasks: 회원 랭킹 산정 및 조회

**Input**: Design documents from `specs/kb-123-member-ranking/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-ranking-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트(Red)를 먼저 작성하고 Red 를 확인한 뒤 Green → Refactor 했다. 모든 테스트는 Kotest `BehaviorSpec`(given/`when`/then 한국어).

**Organization**: 스토리별로 묶어 각각 독립 검증한다. 워크트리 `~/source_code/meogo/meogo-server-kb-123`(브랜치 `kb-123-member-ranking`)에서 작업한다.

**설계 확정 이력**: (1) 스캔 횟수 단위 = 메뉴판 1장 1회 → `scan_history` 행 수 집계 안 폐기. (2) 랭킹은 **`Member` 애그리거트의 하위 개념** → 별도 카운터 테이블(`member_ranking`) 안을 폐기하고 `member` 컬럼으로 이전. (3) 정책의 **카운트 3종**(`scan_count`·`review_count`·`unique_reviewed_food_count`)을 모두 컬럼으로 두고 가입 시 0 초기화, 이후 카운트업만(현재는 스캔만 오른다). 동시성(read-modify-write 유실)은 초기 단계라 의도적으로 감수하며, 필요해지면 이벤트 기반 집계로 전환한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 선행 의존 없음)
- **[Story]**: 해당 사용자 스토리(US1/US2/US3)
- 파일 경로는 저장소 루트 기준

---

## Phase 1: Setup

- [x] T001 워크트리 기준선 그린 확인 — `./gradlew test`(Docker 필요, MySQL Testcontainers)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US2·US3 이 모두 의존하는 산정 로직과 스캔 횟수 카운터.

- [x] T002 [P] **Red** — `core/member/src/test/kotlin/com/meogo/core/member/MemberRankingTest.kt`: 점수 공식, 검증 케이스(리뷰 8·고유음식 6·스캔 9 → 128 · explorer · nextTier regular · pointsToNext 52), 활동 0 → newcomer, 음수 카운트 거부, breakdown 합 = score
- [x] T003 [P] **Red** — `core/member/src/test/kotlin/com/meogo/core/member/MemberTest.kt`: 가입 직후 `scanCount` 0·랭킹 최하 등급, `recordScan()` 이 새 인스턴스에서 1 올리고 원본 불변, 프로필 갱신 시 스캔 횟수 보존
- [x] T004 **Green** — `core/member/src/main/kotlin/com/meogo/core/member/RankingTier.kt`: 7단계 enum(안정 키·level·minScore), `of(score)`(경계값은 상위 등급), `next`
- [x] T005 **Green** — `core/member/src/main/kotlin/com/meogo/core/member/MemberRanking.kt`: 카운트 3개를 받는 불변 값 객체(score·tier·nextTier·pointsToNext·항목별 점수)
- [x] T006 **Green** — 랭킹을 `Member` 애그리거트에 편입: `core/member/.../Member.kt`(`scanCount`·`recordScan()`·`ranking()`, 가입 시 0), `infra/persistence/.../member/MemberJpaEntity.kt`(`scan_count` 컬럼 + `applyDomain`), `MemberRepositoryAdapter.kt`, 마이그레이션 `app/api/src/main/resources/db/migration/V2026.07.13.00.19.27__add_member_ranking_counts.sql`(카운트 3종 DEFAULT 0). 영속 왕복은 `MemberRepositoryAdapterTest`(가입 시 0·카운트업 영속·프로필 갱신 시 보존)
- [x] T007 **Red** — `application/client/src/test/kotlin/com/meogo/application/client/member/MemberRankingUseCaseTest.kt`(페이크 회원 리포지토리): 회원의 스캔 횟수가 점수에 반영, 리뷰·다양성 0, 존재하지 않는 회원이면 `MEMBER_NOT_FOUND`
- [x] T008 **Green** — `application/client/src/main/kotlin/com/meogo/application/client/member/MemberRankingUseCase.kt` + `dto/MemberRankingResult.kt`(경계 DTO — app:api 가 도메인 타입을 보지 않게)

---

## Phase 3: 스캔 시 카운트업 (US1·US2 의 데이터 원천)

**Goal**: 메뉴판 스캔 1회마다 회원의 스캔 횟수가 1 오른다.

- [x] T009 **Red** — `application/client/src/test/kotlin/com/meogo/application/client/scan/usecase/ScanUseCaseHistoryTest.kt`: 메뉴판 1장에 음식이 여러 개 매칭돼도 1회만, 두 번 스캔하면 2회, 매칭 0건이어도 1회, 회원 간 격리
- [x] T010 **Green** — `application/client/src/main/kotlin/com/meogo/application/client/scan/usecase/ScanUseCase.kt`: `assessMenuBoard` 끝에서 회원 로드 → `member.recordScan()` → `memberRepository.update`

---

## Phase 4: User Story 1 — 프로필 탭에서 내 등급을 한눈에 본다 (P1) 🎯 MVP

**Independent Test**: 회원 토큰으로 `GET /api/v1/members/me/profile` 호출 → 기존 필드 + `ranking` 요약(breakdown 없음).

- [x] T011 [US1] **Red** — `MemberProfileUseCaseTest.kt`: 프로필 결과에 랭킹 요약이 담긴다(스캔 40회 → 80점·explorer·pointsToNext 100 / 활동 0 → newcomer)
- [x] T012 [US1] **Red** — `app/api/src/test/kotlin/com/meogo/app/api/member/MemberControllerTest.kt`: 가입 직후 회원은 0점·newcomer, 스캔 40회 회원은 80점·explorer, `breakdown` 은 없다, 미인증 401
- [x] T013 [US1] **Green** — `application/client/.../dto/MyProfileResult.kt`(랭킹 요약 필드) + `MemberProfileUseCase` 가 `MemberRankingUseCase` 호출
- [x] T014 [US1] **Green** — `app/api/.../member/MyProfileResponse.kt`(`ranking` 요약) + `MemberApi.kt` 프로필 조회 swagger 설명 갱신

---

## Phase 5: User Story 2 — 랭킹 상세에서 점수 내역을 확인한다 (P2)

**Independent Test**: `GET /api/v1/members/me/ranking` → 요약 + breakdown 3항목, 합 = 총점, 프로필 요약과 값 일치.

- [x] T015 [US2] **Red** — `MemberControllerTest.kt`: breakdown 3항목(scans count·points, reviews·diversity 0), points 합 = score, 미인증 401, **프로필 요약과 상세의 다섯 값 일치**(FR-008)
- [x] T016 [US2] **Green** — `app/api/.../member/MemberRankingResponse.kt`(요약 + breakdown)
- [x] T017 [US2] **Green** — `MemberApi.kt` 에 `GET /me/ranking` swagger 문서 + `MemberController.kt` 핸들러(`@AuthMemberId`). 인증 설정 변경 없음(`/api/v1/members/*` 는 JWT 필터가 이미 덮는다)

---

## Phase 6: User Story 3 — 최고 등급에 도달한다 (P3)

**Independent Test**: 누적 1000점 이상 회원 → 두 응답 모두 `nextTier`·`pointsToNext` 가 null.

- [x] T018 [P] [US3] **Red** — `MemberRankingTest.kt`: 경계값 전수(0·30·80·180·350·600·1000)와 직전 값(29·79·999), 1000점 이상이면 korean_at_heart(level 7) + nextTier·pointsToNext null
- [x] T019 [US3] **Red** — `MemberControllerTest.kt`: 스캔 500회(=1000점) 회원의 프로필·랭킹 상세 응답 모두 `nextTier`·`pointsToNext` 가 null 로 직렬화
- [x] T020 [US3] **Green** — `RankingTier.next` / `MemberRanking.pointsToNext` 의 최고 등급 처리와 응답 DTO nullable 매핑 확인(도메인 구현으로 이미 통과 — 테스트만 추가)

---

## Phase 7: Polish & Cross-Cutting

- [x] T021 전체 테스트 그린 — `./gradlew test`(ArchUnit `ModuleBoundaryTest` 포함)
- [x] T022 [P] Kotlin 주석 금지 규약 확인 — 신규·수정 `.kt` 에 주석 없음
- [x] T023 설계 문서 동기화 — plan/research/data-model/contracts/quickstart 를 "Member 애그리거트 하위 랭킹 + member.scan_count 카운트업" 으로 갱신(초안의 "스키마 무변경·이력 행 수 집계" 와 중간안 "별도 카운터 테이블" 모두 폐기)
- [ ] T024 수동 검증 — `quickstart.md` 절차대로 `SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun` 후 스캔 → 프로필·랭킹 상세 응답 일치·401 확인, Swagger "회원" 태그 노출 확인
- [ ] T025 draft PR — `open-draft-pr-to-develop` 스킬. 본문에 **계약 변경 2건**을 명시: (1) 프로필 응답에 `ranking` 요약 추가, (2) `member` 카운트 컬럼 3종 추가 마이그레이션 + 스캔 시 카운트업(기존 회원 카운트는 소급 없음)

---

## Dependencies & Execution Order

- Phase 1 → Phase 2(산정 로직·카운터) → Phase 3(카운트업) → US1 / US2 → US3 → Polish
- US1·US2 는 서로 의존하지 않지만 둘 다 `MemberControllerTest.kt` 를 수정하므로 순차로 처리했다.

## 남은 리스크

- **기존 회원의 스캔 횟수는 0에서 시작한다.** 배포 이후 스캔부터 쌓이며, `scan_history` 로 소급 집계하지 않는다(메뉴판 단위 복원이 불가능하다 — 음식 단위 행만 있다).
- **동시 스캔 시 카운트 1회가 유실될 수 있다**(read-modify-write). 의도적 수용 — 관측되면 이벤트 기반 집계로 전환한다.
- 로컬 DB 에 이전 `member_ranking` 마이그레이션을 적용했다면 테이블과 `flyway_schema_history` 행을 정리해야 부팅된다.
- KB-124(프로필 수정 부분 수정 전환)와 `MemberProfileUseCase`·`MemberController`·`MemberControllerTest` 가 겹친다 — 먼저 머지된 쪽 기준으로 리베이스.
