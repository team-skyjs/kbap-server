---
description: "Task list for 서비스 조회 메서드 네이밍 get 통일"
---

# Tasks: 서비스 조회 메서드 네이밍 get 통일

**Input**: Design documents from `specs/kb-170-service-lookup-get-naming/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md

**Tests**: Test-First(원칙 I)은 **계약이 이동하는 `getReadyFood`(null→throw)** 에 명시 적용한다(테스트를 먼저 `shouldThrow` 로 Red → 구현). 그 외 리네임은 기존 테스트를 새 이름으로 **동반 갱신**해 Green 을 유지한다(계약 무변경이라 Red 단계 없음).

**Organization**: 리네임은 "메서드 선언 + 전 호출부 + 테스트 참조"를 한 task 에서 원자적으로 바꿔 매 task 후 빌드가 Green 이도록 한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1(리네임 본체)·US2(규약 문서)

## Path Conventions

- 도메인 서비스: `domain/<context>/src/main/kotlin/com/kbap/domain/<context>/`
- 애플리케이션: `application/src/main/kotlin/com/kbap/application/`
- 컨트롤러: `app/api/src/main/kotlin/com/kbap/app/api/`
- 테스트: 각 모듈 `src/test/...` 미러

---

## Phase 1: Setup (기준선 확인)

**Purpose**: 리네임 시작 전 Green 기준선과 현재 `find*` 인벤토리 고정

- [X] T001 기준선 확인 — `./gradlew test` 로 전 모듈 Green 확인 후, `grep -rn "fun find" domain application --include="*.kt" | grep -v /test/ | grep -v findOrSignUp` 결과를 리네임 대상 목록으로 기록(quickstart.md §1)

---

## Phase 2: Foundational

**해당 없음** — 새 인프라·모듈·엔티티가 없어 공통 선행 작업이 존재하지 않는다. Phase 3 로 바로 진입한다.

---

## Phase 3: User Story 1 - 조회 메서드 이름으로 계약을 예측한다 (Priority: P1) 🎯 MVP

**Goal**: 서비스 조회 메서드를 `get~`(없으면 예외/non-null)으로 통일하고 null 정상값만 `get~OrNull`, 페이지는 이름·타입 일치, List 로더는 컬렉션 규칙(`get~s`, internal)으로 흡수한다.

**Independent Test**: `grep "fun find"` 결과가 0(유비쿼터스 `findOrSignUp` 제외)이고 `./gradlew test` 가 Green 이면 통과(quickstart §1~4).

> 아래 task 들은 **호출부 파일이 서로 겹친다**(`HomeApplicationService`·`FoodService`·`FoodServiceTest`). 따라서 대부분 **순차** 실행이며 [P] 는 파일 충돌이 없는 경우에만 표기한다. 각 task 후 커밋한다.

- [X] T002 [P] [US1] `AvoidanceCatalogService.findByCodes` → `getSubstancesByCodes` 로 개명 — 선언(`domain/avoidance/.../AvoidanceCatalogService.kt`) + 호출부(`domain/food/.../FoodService.kt` getDetail, `application/.../home/HomeApplicationService.kt`) + 테스트 참조 갱신, `./gradlew test`

- [X] T003 [P] [US1] `ScanService.findRecentReadyFoodIds` → `getRecentReadyFoodIds` 로 개명 — 선언(`domain/scan/.../ScanService.kt`) + 호출부(`application/.../home/HomeApplicationService.kt`) + `ScanServiceTest` 참조 갱신, `./gradlew test`

- [X] T004 [US1] `MemberService` 회원 조회 분리 — `findActive` → `getMemberOrNull`, private `findActiveOrThrow` → **public `getMember`** 로 승격(`domain/member/.../MemberService.kt`). 호출부 계약별 배분: `getAvoidedCodes`·`HomeApplicationService`(게스트) → `getMemberOrNull`; `AuthApplicationService.refresh`(`== null` → INVALID_REFRESH_TOKEN) → `getMemberOrNull`; `AuthApplicationService.withdraw`·내부 4곳(completeOnboarding·updateProfile·getMyProfile·getRanking·withdraw) → `getMember`. `MemberServiceTest`·`AuthApplicationServiceTest` 갱신, `./gradlew test`

- [X] T013 [P] [US1] `ImageUploadService.findVerifiedImage` → `verifyImageAccess` 로 재분류 개명(FR-010) — 선언만 변경(`domain/image/.../ImageUploadService.kt`), 반환 타입 `UploadedImage?`·읽기전용 트랜잭션·미사용 상태 유지, `ScanService` 의 TODO 주석 참조 문구도 `verifyImageAccess` 로 동기화(`domain/scan/.../ScanService.kt:33`), `./gradlew test`

- [X] T005 [US1] `BookmarkService` 개명 — `findBookmarks` → `getBookmarkPage`(반환 `BookmarkPage`), `findBookmarkedFoodIds` → `getBookmarkedFoodIds`(`domain/bookmark/.../BookmarkService.kt`) + 호출부(grep 로 확인 — 북마크 플래그 소비처) + `BookmarkServiceTest`·`BookmarkControllerTest` 갱신, `./gradlew test`

- [X] T006 [US1] `FoodService` 컬렉션 조회 개명 — `findRandomReady`→`getRandomReadyFoods`, `findAllReadyByIds`→`getReadyFoodsByIds`, `findByKoreanMatchKeys`→`getFoodsByKoreanMatchKeys`(`domain/food/.../FoodService.kt`) + 호출부(`HomeApplicationService`, `ScanService`, `BookmarkService`) + `FoodServiceTest` 갱신, `./gradlew test`

- [X] T007 [US1] `FoodService` 페이지 조회 개명 — **로더 선행 순서 준수**(research §4): ① `searchFoodPage`(로더,List)→`getFoodsByKeyword`(internal) → ② `search`→`searchFoodPage` → ③ `findFoodPage`(로더,List)→`getFoods`(internal) → ④ `browse`→`getFoodPage`(`domain/food/.../FoodService.kt`) + 호출부(`app/api/.../food/FoodController.kt`) + `FoodServiceTest`·`FoodControllerTest` 갱신, `./gradlew test`

### Test-First: `getReadyFood`(계약 이동 — null→throw)

- [X] T008 [US1] (Red) `FoodServiceTest` 에서 `findReadyById` 의 미존재·미완성·소프트삭제 케이스(`shouldBe null`/`shouldBeNull` 3곳)를 `shouldThrow<BusinessException>`(FOOD_NOT_FOUND) 로 변경하고, 미리 이름을 `getReadyFood` 로 참조해 **테스트가 실패(Red)** 함을 확인(`domain/food/src/test/.../FoodServiceTest.kt`)

- [X] T009 [US1] (Green) `findReadyById`(`Food?`) → `getReadyFood`(`Food`, 내부 `?: throw BusinessException(FOOD_NOT_FOUND)`)(`domain/food/.../FoodService.kt`) + 호출부 2곳(`getDetail`, `BookmarkService.bookmark`)의 `?: throw FOOD_NOT_FOUND` 꼬리 제거 + `shouldNotBeNull()` 7곳을 반환값 직접 사용으로 정리, `./gradlew test` Green 확인

**Checkpoint**: 서비스 public 조회에 `find` 접두 0(예외: `findOrSignUp`), 페이지 이름·타입 일치, 전체 테스트 Green → US1 독립 완료(MVP).

---

## Phase 4: User Story 2 - 네이밍 규약이 문서로 고정된다 (Priority: P2)

**Goal**: CLAUDE.md 규약이 get 통일 규칙을 반영해 재발을 막는다.

**Independent Test**: CLAUDE.md "서비스 메서드 네이밍" 절에 get 통일 + `get~OrNull` 예외 + 규약 밖 구분이 명시됨(quickstart §6).

- [X] T010 [US2] `CLAUDE.md` "서비스 메서드 네이밍 (고정)" 절 갱신 — 단건 `get~`(없으면 예외/non-null)/`get~OrNull`(null 정상값만), 컬렉션 `get~s`(빈 값 허용), 페이지 `get~Page`(이름·반환타입 일치), `find` 접두 폐기, 규약 밖(유비쿼터스 동사 `search`·`findOrSignUp`·보조·행위) 명시. 예시 `getAvoidedCodes` 를 컬렉션 get 예로 유지. **MemberService 서비스 계약 한 줄 추가**: "MemberService 조회는 항상 active(member_status=ACTIVE) 회원만 노출한다 — `getMember`/`getMemberOrNull` 이름에 active 를 생략하는 근거"

**Checkpoint**: 규약 문서가 코드 상태와 일치.

---

## Phase 5: Polish & Cross-Cutting

- [X] T011 [P] quickstart.md 검증 런북 전 항목 실행 — `grep` 게이트(§1~2, `find` 0건·페이지 이름/타입 일치), `./gradlew test`(§3), ArchUnit `ModuleBoundaryTest`(§3), 핵심 API 통합 테스트 Green(§4)
- [ ] T012 [P] (선택) `FoodService.getDetail` → `getFoodDetail` 명확화 개명 + 호출부(`FoodController`)·테스트 — 필수 완료 기준 아님(Assumptions), 원할 때만

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(T001)**: 즉시 시작, 기준선 고정
- **Foundational**: 없음
- **US1(T002~T009)**: T001 이후. 대부분 순차(호출부 파일 공유) — 아래 순서 권장
- **US2(T010)**: US1 완료 후(코드가 규약과 일치해야 문서화 의미)
- **Polish(T011~T012)**: US1(필수)·US2 완료 후

### US1 내부 순서

1. T002·T003 (리프 서비스, 서로 [P] 가능 — 파일·테스트 비겹침)
2. T004 (Member — Auth/Home 호출부)
3. T005 (Bookmark)
4. T006 (Food 컬렉션)
5. T007 (Food 페이지 — 로더 선행 순서 필수)
6. T008 → T009 (getReadyFood Test-First: Red → Green)

- T004·T006·T007 는 `HomeApplicationService`/`FoodService`/`FoodServiceTest` 를 공유하므로 **순차**.
- T008 은 T006·T007 이후에 하면 `FoodServiceTest` 편집 충돌을 피한다.

### Parallel Opportunities

- **T002 ‖ T003** — avoidance·scan 선언과 테스트가 겹치지 않아 병렬 가능(단 둘 다 `HomeApplicationService` 호출부를 건드리므로, 한 사람이 순차로 하거나 호출부 라인만 조심). 실질적으로 단일 개발자 순차 실행 권장.
- **T011 ‖ T012** — 검증과 선택 개명은 독립.
- US1 대부분은 호출부 공유로 병렬 이득이 적다 — 순차가 안전.

---

## Implementation Strategy

### MVP (US1)

1. T001 기준선
2. T002~T009 리네임(순차, 매 task 후 `./gradlew test` + 커밋)
3. **STOP & VALIDATE**: `find` 0건 + 전체 Green + API 계약 무변경(quickstart §4)

### Incremental

1. US1 완료 → 코드 리네임 반영(MVP)
2. US2(T010) → 규약 문서화
3. Polish(T011) → 검증 런북, (T012) 선택 개명

---

## Notes

- [P] = 다른 파일·의존 없음. 이 기능은 호출부가 교차해 [P] 기회가 제한적 — 순차 + task 단위 커밋이 기본.
- 매 리네임 task 후 `./gradlew test` Green 을 확인하고 커밋(작업 단위 커밋, 원칙 준수).
- 계약 무변경이 원칙(FR-006) — `getReadyFood` 만 계약 이동이며 최종 동작은 기존 `find + ?: throw` 와 동일.
- 범위 밖: `:app:batch`, `FoodScoringSource.nextChunk`, 행위·보조 메서드, DB/Flyway/엔티티/모듈 그래프.
