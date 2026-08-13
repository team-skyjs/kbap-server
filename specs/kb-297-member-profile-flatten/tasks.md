# Tasks: 회원 프로필 JSON 컬럼 평탄화 (KB-297)

**Input**: Design documents from `specs/kb-297-member-profile-flatten/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (contracts/ 없음 — 외부 API 불변)

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리의 테스트를 구현보다 먼저 작성하고 Red 를 확인한다.

**Organization**: 스토리별 구성. 이 기능은 내부 저장 구조 리팩터링이라 스토리 간 순차 의존이 강하다(US1 백필 → US2 엔티티 전환) — 병렬 여지는 제한적이며 태스크 단위 [P] 로만 표시한다.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- 엔티티·값 객체: `common/src/main/kotlin/com/kbap/common/domain/member/model/`
- 마이그레이션: `api/src/main/resources/db/migration/` (버전 = 파일 생성 시점 로컬 시각 `Vyyyy.MM.dd.HH.mm.ss__desc.sql`, 각 파트 두 자리 zero-pad)
- 테스트: 각 모듈 `src/test/kotlin/...` 미러 구조, Kotest BehaviorSpec(given/when/then 한국어)

---

## Phase 1: Setup

해당 없음 — 기존 모듈·빌드 구성 그대로 사용한다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 신규 컬럼 스키마 — 백필 테스트(US1)와 엔티티 전환(US2)이 모두 이 스키마를 전제한다

- [x] T001 schema 마이그레이션 작성: `api/src/main/resources/db/migration/V<생성시각>__member_profile_flatten_schema.sql` — `ALTER TABLE member ADD COLUMN` 4종: `spiciness_preference ENUM('SKIP','NONE','MILD','MEDIUM','HOT','EXTREME') NOT NULL DEFAULT 'SKIP'`, `country_code VARCHAR(2) NULL`, `profile_image_url VARCHAR(512) NULL`, `avoidance_substance_codes JSON NOT NULL`(MySQL 8 — `DEFAULT (JSON_ARRAY())`). 기존 `profile` 컬럼은 유지(구코드 호환 — drop 은 T008)

**Checkpoint**: 스키마 준비 완료 — US1 시작 가능

---

## Phase 3: User Story 1 - 기존 회원 데이터 무손실 이전 (Priority: P1) 🎯 MVP

**Goal**: profile JSON 의 모든 데이터가 신규 컬럼 4종으로 한 건도 유실·변형 없이 이관된다

**Independent Test**: JSON 시드 → backfill SQL 실행 → 컬럼 값 대조가 테스트 하나로 독립 검증된다

### Tests for User Story 1 (Red 먼저) ⚠️

- [x] T002 [US1] 백필 통합 테스트 작성(Red 확인): `api/src/test/kotlin/com/kbap/api/migration/MemberProfileBackfillTest.kt` — Testcontainers MySQL 에 **별도 데이터베이스**를 만들고 Flyway API 로 `target=<T001 schema 버전>` 까지 적용 → profile JSON 행 시드 → `target=<backfill 버전>` 으로 추가 migrate → 신규 컬럼 4종 대조. 시드 케이스: ① 전 항목 채움 ② legacy 선행 슬래시 이미지 경로(`/images/...` → 무슬래시 기대) ③ countryCode JSON null ④ avoidanceSubstanceCodes 속성 결손(→ 빈 배열 기대) ⑤ 소프트 삭제(status=DELETED) 회원 포함. 백필 파일이 아직 없으므로 실패(Red)를 확인한다. 주의: 앱 컨텍스트의 공유 스키마를 건드리지 않는다(전용 DB 사용 — drop 마이그레이션(T008) 추가 후에도 target 덕에 깨지지 않는 구조)

### Implementation for User Story 1

- [x] T003 [US1] backfill 마이그레이션 작성(Green): `api/src/main/resources/db/migration/V<생성시각>__member_profile_flatten_backfill.sql` — 단일 `UPDATE member SET` 로 `JSON_UNQUOTE(JSON_EXTRACT(profile,'$.spicinessPreference'))` → `spiciness_preference`, `$.countryCode`(JSON null → SQL NULL) → `country_code`, `$.profileImageUrl` 에 `TRIM(LEADING '/' ...)` → `profile_image_url`, `COALESCE(JSON_EXTRACT(profile,'$.avoidanceSubstanceCodes'), JSON_ARRAY())` → `avoidance_substance_codes`. 소프트 삭제 포함 전 행 대상(WHERE 없음). T002 테스트 Green 확인

**Checkpoint**: 백필 검증 완료 — 데이터 이전 안전성 확보(SC-001)

---

## Phase 4: User Story 2 - 프로필 기능 동작 불변 (Priority: P2)

**Goal**: Member 엔티티가 JSON 대신 신규 컬럼을 사용하되, 온보딩·프로필 수정·조회의 외부 동작(응답·검증·에러)이 변경 전과 동일하다

**Independent Test**: 기존 온보딩·프로필 관련 테스트 전체 재실행으로 독립 검증(SC-002·SC-003)

### Tests for User Story 2 (Red 먼저) ⚠️

- [x] T004 [P] [US2] `common/src/test/kotlin/com/kbap/common/domain/member/model/MemberTest.kt` 수정(Red 확인): `profileJson = MemberProfileJson(...)` 생성 구문 → 신규 필드(`spicinessPreference`·`countryCode`·`profileImageUrl`·`avoidanceSubstanceCodes: List<String>`) 기반으로 교체. 기존 given/when/then 의미(온보딩·수정·탈퇴 동작)는 불변 — 컴파일 실패가 Red
- [x] T005 [P] [US2] `common/src/test/kotlin/com/kbap/common/domain/member/model/MemberProfileTest.kt` 수정(Red 확인): MemberProfileJson 직렬화/역직렬화(legacy 키 무시·저장 포맷) 스펙 삭제 — JSON 복합 문서 표현 자체가 소멸. 값 검증(updatedWith·validatedImagePath 등) 스펙은 유지. 로드 시 trimStart 정규화 제거(R4)에 따라 관련 기대를 쓰기 검증 쪽으로 정리

### Implementation for User Story 2

- [x] T006 [US2] `common/src/main/kotlin/com/kbap/common/domain/member/model/Member.kt` 리팩터(Green): `profileJson` 필드 제거 → data-model.md 매핑대로 필드 4종(`@Enumerated spicinessPreference`, `countryCode String?`, `profileImageUrl String?`, `@JdbcTypeCode(SqlTypes.JSON) avoidanceSubstanceCodes List<String>`) 추가. `profile` getter 는 `MemberProfile.of(...)` 직접 조립(trimStart 제거 — R4), `updateProfile(profile)` 은 4필드 대입. `MemberProfileJson.kt` 삭제. T004·T005 Green 확인
- [x] T007 [US2] api 테스트 시드·검증부 수정: `api/src/test/kotlin/com/kbap/api/admin/AdminMemberPageControllerTest.kt`(MemberProfileJson 생성자 → 신규 필드), `api/src/test/kotlin/com/kbap/api/community/PostingReadControllerTest.kt`(raw SQL 시드의 profile JSON → 신규 컬럼 4종), `api/src/test/kotlin/com/kbap/api/member/MemberControllerTest.kt`(424행 근방 profile JSON 컬럼 직접 검증 → 신규 컬럼 검증. 649행 backfill_default_profile_image 리소스 참조는 불변)
- [x] T008 [US2] drop 마이그레이션 작성: `api/src/main/resources/db/migration/V<생성시각>__member_profile_drop_json.sql` — `ALTER TABLE member DROP COLUMN profile`. T006 이후에만(코드가 JSON 을 더는 읽지 않는 시점). 적용 후 `:api:test` 부팅(`ddl-auto=validate`)이 엔티티↔최종 스키마 정합을 검증
- [x] T009 [US2] 회귀 확인: `./gradlew :common:test :api:test` — 온보딩·프로필 수정·조회·admin·community·review 기존 스펙 전부 통과(SC-002·SC-003)

**Checkpoint**: 외부 동작 불변 검증 완료 — JSON 컬럼 제거 상태로 전체 스펙 통과

---

## Phase 5: User Story 3 - 프로필 항목별 데이터 정합성 강제 (Priority: P3)

**Goal**: 신규 컬럼이 항목 단위 직접 조회·필터링을 지원하고, 제약(ENUM·길이·NOT NULL)이 저장소 수준에서 강제됨을 확인한다

**Independent Test**: 컬럼 단위 SQL 필터 조회가 문서 파싱 없이 동작하는지 백필 테스트에서 검증

### Tests for User Story 3 (검증 전용 — 테스트 추가만)

- [x] T010 [US3] `api/src/test/kotlin/com/kbap/api/migration/MemberProfileBackfillTest.kt` 에 항목 단위 조회 스펙 추가: 백필 완료 상태에서 `WHERE country_code = ?` 필터 조회·`spiciness_preference` 값 직접 SELECT 가 JSON 파싱 없이 동작함을 검증(US3 수용 시나리오 1·2). ENUM 외 값 INSERT 거부 등 MySQL 자체 동작 테스트는 두지 않는다

**Checkpoint**: 전 스토리 검증 완료

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T011 전체 회귀 + 마무리: `./gradlew build`(ArchUnit 포함) 통과 확인, quickstart.md 검증 명령 3종 실행, 논리 단위 커밋 정리(스키마/백필 → 엔티티 전환 → drop)

---

## Dependencies & Execution Order

```text
T001 (schema)
 └─ T002 (백필 테스트 Red) ─ T003 (백필 Green)          [US1]
     └─ T004 ∥ T005 (엔티티 테스트 Red)                  [US2]
         └─ T006 (엔티티 Green) ─ T007 (api 테스트 시드)
             └─ T008 (drop) ─ T009 (회귀)
                 └─ T010 (항목 조회 검증)                [US3]
                     └─ T011 (전체 build·마무리)
```

- **US2 는 US1 완료 후 시작한다** — 엔티티 전환(T006)은 신규 컬럼에 데이터가 있어야 기존 통합 테스트(Flyway 전체 적용 DB)와 정합. 스토리 병렬화는 하지 않는다(단일 리팩터링 체인).
- [P] 는 T004∥T005 (서로 다른 테스트 파일, 상호 의존 없음) 뿐이다.

## Implementation Strategy

- **MVP = US1**: T001→T003 까지가 "데이터를 안전하게 옮길 수 있다"는 증명 — 여기서 멈춰도 스키마·백필은 무해(구코드는 JSON 을 계속 사용).
- 이후 US2 가 실질 전환(엔티티+drop), US3 는 검증 보강, T011 이 최종 게이트.
- 커밋 단위: T001+T002+T003 (백필) / T004~T007 (엔티티 전환) / T008+T009 (drop+회귀) / T010+T011 (검증 마무리).

## Notes

- 마이그레이션 버전은 **각 파일 생성 시점의 실제 로컬 시각**으로 짓는다 — 세 파일이 T001 → T003 → T008 순서로 생성되므로 timestamp 순서가 적용 순서를 보장한다.
- 백필 테스트는 전용 데이터베이스 + Flyway `target` 방식이라 T008(drop) 추가 후에도 깨지지 않는다.
- 테스트 스타일: Kotest BehaviorSpec, given/when/then 한국어, 통합 테스트는 `:common` testFixtures 의 MySqlContainerConfig.
