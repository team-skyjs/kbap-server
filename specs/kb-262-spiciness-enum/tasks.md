# Tasks: 사용자 프로필 맵기 설정 ENUM 전환

**Input**: Design documents from `/specs/kb-262-spiciness-enum/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-api.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 원칙 I) — 각 스토리의 테스트를 구현 전에 작성하고 Red 확인 후 구현한다. 모든 테스트는 Kotest `BehaviorSpec`, given/when/then 한국어.

**Organization**: 스토리별 독립 구현·검증. 단, 본 기능은 단일 표현 전환이라 US1 이 도메인 코어를 소유하고 US2(이관)·US3(관리자)는 그 위의 얇은 증분이다.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

이번 기능은 신규 모듈·의존성·초기화가 없다 — Setup 없음. (브랜치·spec 디렉터리는 speckit 훅이 이미 생성.)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 enum 타입과 에러 메시지. TDD — enum 테스트가 먼저다.

- [x] T001 [Red] `SpicinessPreference` 단위 테스트 작성 — `common/src/test/kotlin/com/kbap/common/domain/member/model/SpicinessPreferenceTest.kt`: 6값 존재, `from("HOT")` 성공, `from("SUPER_HOT")`·`from("5")` → `BusinessException(MEMBER-009)`. 컴파일 실패(클래스 부재)로 Red 확인
- [x] T002 [Green] `SpicinessPreference` enum 신규 작성 — `common/src/main/kotlin/com/kbap/common/domain/member/model/SpicinessPreference.kt`: SKIP·NONE·MILD·MEDIUM·HOT·EXTREME + companion `from(raw: String)`(미지 값 → MEMBER-009). T001 Green 확인
- [x] T003 `ErrorCode.INVALID_SPICINESS_PREFERENCE` 메시지 갱신 — `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt`: "맵기 선호는 SKIP·NONE·MILD·MEDIUM·HOT·EXTREME 중 하나여야 합니다"(코드 MEMBER-009·상태 400 유지)

**Checkpoint**: enum + 에러 코드 준비 완료 — 스토리 구현 시작 가능

---

## Phase 3: User Story 1 - 온보딩·프로필에서 맵기 단계 선택 (Priority: P1) 🎯 MVP

**Goal**: 온보딩·프로필 수정·내 프로필 조회 전 구간이 6단계 enum 문자열을 주고받는다. 도메인(MemberProfile)·영속 JSON·dto·API 계약·Swagger 전환.

**Independent Test**: 온보딩 요청에 `"HOT"` 을 담아 호출하고 내 프로필 조회로 `"HOT"` 이 돌아오는지, 6단계 외 값이 400 MEMBER-009 로 거절되는지 확인.

### Tests for User Story 1 (Red 먼저) ⚠️

- [x] T004 [P] [US1] [Red] `MemberProfileTest` 를 enum 기준으로 재작성 — `common/src/test/kotlin/com/kbap/common/domain/member/model/MemberProfileTest.kt`: `empty()` → SKIP, `updatedWith(spicinessPreference = "MILD")` 반영, 미지 문자열 → MEMBER-009, null → 기존 값 유지. `MemberTest.kt` 의 정수 사용처도 함께 갱신
- [x] T005 [P] [US1] [Red] `MemberControllerTest` 를 enum 계약으로 갱신 — `api/src/test/kotlin/com/kbap/api/member/MemberControllerTest.kt`: 온보딩 `"HOT"` 왕복, `"SKIP"` 온보딩, 수정 `"MILD"` 반영·생략 시 유지, `"SUPER_HOT"`·정수 `7` → 400 MEMBER-009, 내 프로필 응답 문자열 검증
- [x] T006 [US1] [Red] 나머지 테스트 지원 코드의 정수 사용처 enum 문자열로 갱신 — `api/src/test/kotlin/com/kbap/api/auth/AuthControllerTest.kt`·`scenario/ScenarioApiDriver.kt`·`home/HomeTestSeed.kt`·`food/FoodTestSeed.kt`(member 프로필 시드 부분만 — food.spiciness 정수는 그대로). 컴파일·assertion 실패로 Red 확인

### Implementation for User Story 1

- [x] T007 [US1] [Green] `MemberProfile` 전환 — `common/src/main/kotlin/com/kbap/common/domain/member/model/MemberProfile.kt`: 필드 `SpicinessPreference` 타입, `SPICINESS_UNSET` 상수·init require·`Spiciness` import 삭제, `empty()` → SKIP, `updatedWith(spicinessPreference: String?)` + `validatedSpiciness` → `SpicinessPreference.from`
- [x] T008 [US1] [Green] `MemberProfileJson` 전환 — `common/src/main/kotlin/com/kbap/common/domain/member/model/MemberProfileJson.kt`: 필드 `SpicinessPreference = SpicinessPreference.SKIP`(JSON 에 enum 이름 문자열 저장, 결손 → SKIP)
- [x] T009 [US1] [Green] 도메인 dto 전환 — `common/src/main/kotlin/com/kbap/common/domain/member/dto/MemberProfileInput.kt`(`String`)·`ProfileUpdateInput.kt`(`String?`)·`MyProfileResult.kt`(`String` = `enum.name`), `MemberService` 경유 타입 정합
- [x] T010 [US1] [Green] API 요청/응답 전환 — `api/src/main/kotlin/com/kbap/api/member/OnboardingRequest.kt`(`String`)·`ProfileUpdateRequest.kt`(`String?`)·`MyProfileResponse.kt`(`String`)
- [x] T011 [US1] Swagger 문서 갱신 — `api/src/main/kotlin/com/kbap/api/member/MemberApi.kt`: operation 설명 "-1(미설정) 또는 0~10 정수" → 6단계 문자열·`"SKIP"` 명시 전송 규약, ExampleObject 4건의 정수 값을 enum 문자열로 교체 (FR-004)
- [x] T012 [US1] `:common`·`:api` member 테스트 Green 확인 후 Refactor — `./gradlew :common:test :api:test --tests "*Member*"` 통과, 잔여 정수 표현·불필요 import 정리

**Checkpoint**: US1 완결 — 신규 데이터 기준 전 구간 enum 왕복 동작

---

## Phase 4: User Story 2 - 기존 회원 데이터 자동 이관 (Priority: P2)

**Goal**: 저장된 정수(-1~10)·결손 프로필을 Flyway 로 enum 이름 문자열로 이관. 비정상 값 잔존 시 마이그레이션 실패(조용한 유실 금지).

**Independent Test**: 정수 값별 시드 행을 넣고 마이그레이션 적용 후 `profile->>'$.spicinessPreference'` 가 매핑 규칙대로인지 확인.

### Tests for User Story 2 (Red 먼저) ⚠️

- [x] T013 [US2] [Red] 이관 검증 통합 테스트 작성 — `api/src/test/kotlin/com/kbap/api/member/SpicinessMigrationTest.kt`(`@SpringBootTest` + Testcontainers): 마이그레이션 적용 후 정수 프로필 JSON 을 JDBC 로 직접 삽입하는 방식이 아니라, **마이그레이션 이전 형태의 시드를 마이그레이션이 변환했는지**를 검증해야 하므로 — Flyway 적용 전 시드 주입이 불가한 테스트 부팅 구조상, 대표 정수 각각(-1·0·1·3·4·6·7·8·9·10)을 JSON 으로 직접 UPDATE 한 뒤 마이그레이션 SQL 을 스크립트로 재실행해 결과를 검증한다(`ScriptUtils` 또는 JdbcTemplate 로 신규 SQL 리소스 실행). 파일 부재로 Red 확인
- [x] T014 [US2] [Red] 결손·가드 케이스를 T013 에 포함 — 속성 결손 행 → `SKIP`, 범위 밖 정수(예: 99) 잔존 행 → 마이그레이션 실행이 예외로 실패

### Implementation for User Story 2

- [x] T015 [US2] [Green] Flyway 마이그레이션 작성 — `api/src/main/resources/db/migration/V<생성시각>__member_spiciness_enum.sql`: ① 결손 행 `JSON_SET(..., 'SKIP')` ② 정수 행 CASE 매핑(-1→SKIP, 0→NONE, 1~3→MILD, 4~6→MEDIUM, 7~8→HOT, 9~10→EXTREME) ③ 가드 — 6종 외 값 잔존 행 존재 시 `profile = NULL` UPDATE 로 NOT NULL 위반 유도(잔존 0행이면 no-op). 버전은 파일 생성 시점 로컬 시각, 다른 마이그레이션과 순서 독립
- [x] T016 [US2] [Green] T013·T014 Green 확인 + 전체 통합 테스트 부팅 경로(Flyway on)에서 신규 마이그레이션 통과 확인 — `./gradlew :api:test`

**Checkpoint**: US1 + US2 — 기존 데이터 포함 전체 왕복 동작

---

## Phase 5: User Story 3 - 관리자 회원 목록에서 단계 확인 (Priority: P3)

**Goal**: 관리자 회원 조회 응답의 맵기가 enum 문자열로 나간다.

**Independent Test**: 관리자 회원 목록 조회 응답 `spicinessPreference` 가 6단계 문자열인지 확인.

### Tests for User Story 3 (Red 먼저) ⚠️

- [x] T017 [US3] [Red] `AdminControllerTest`(또는 관리자 회원 조회 테스트)에 맵기 문자열 assertion 추가 — `api/src/test/kotlin/com/kbap/api/admin/AdminControllerTest.kt`: `EXTREME` 회원이 `"EXTREME"` 으로 표시. Red 확인(US1 전환 후 컴파일 오류면 그 자체가 Red)

### Implementation for User Story 3

- [x] T018 [US3] [Green] `AdminMemberDetailView.spicinessPreference` 를 `String`(`enum.name`) 으로 전환 — `api/src/main/kotlin/com/kbap/api/admin/AdminMemberQueryService.kt`. T017 Green 확인

**Checkpoint**: 전 스토리 완결

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T019 전체 빌드·회귀 — `./gradlew build` (ArchUnit 포함 전 모듈, FR-007 회귀 0건 확인)
- [x] T020 quickstart.md 수동 검증 시나리오 수행 가능 여부 최종 점검 및 스펙 체크리스트 대조 — `specs/kb-262-spiciness-enum/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: 선행 없음 — 즉시 시작. 모든 스토리를 블록
- **US1 (Phase 3)**: Phase 2 완료 후. 도메인 코어 전환을 소유 — US2·US3 의 사실상 선행
- **US2 (Phase 4)**: Phase 2 후 시작 가능(마이그레이션 SQL 자체는 US1 무관)하나, 검증 테스트가 enum 역직렬화를 전제하므로 US1 후 진행 권장
- **US3 (Phase 5)**: US1 완료 후(도메인 타입 전환에 딸린 얇은 증분)
- **Polish (Phase 6)**: 전 스토리 완료 후

### Within Each User Story

- Red(테스트 작성·실패 확인) → Green(최소 구현) → Refactor
- 도메인(model) → dto → API(request/response) → 문서 순

### Parallel Opportunities

- T004·T005 는 파일이 달라 병렬 작성 가능(둘 다 Red 단계)
- T015(마이그레이션 SQL)는 T007~T010 과 파일 겹침 없음 — US1 구현과 병렬 가능(검증 T013 은 US1 후)
- 나머지는 단일 표현 전환의 연쇄 컴파일 의존이라 직렬 권장

## Implementation Strategy

**MVP = Phase 2 + US1**: 신규 데이터 기준 enum 왕복 완결 — 여기서 멈추고 검증 가능. 단, **배포는 US2(이관) 없이 불가**(기존 정수 데이터가 역직렬화 실패) — 배포 최소 단위는 US1+US2. US3 은 독립 증분.

## Notes

- `Food.spiciness`(0~10)·`Spiciness.RANGE`·food 관련 테스트의 정수는 건드리지 않는다(별개 개념)
- `FoodTestSeed`·`HomeTestSeed` 에서 갱신 대상은 member 프로필 JSON 시드의 `spicinessPreference` 뿐 — food INSERT 의 `spiciness` 정수 컬럼은 그대로
- 시드-동기화 주의: 신규 마이그레이션은 테스트가 리소스 경로로 참조(T013)하므로, 파일명 확정 후 테스트 참조와 일치시킬 것(버전 번호를 테스트 설명에 박지 않는다)
- task/논리 단위마다 커밋
