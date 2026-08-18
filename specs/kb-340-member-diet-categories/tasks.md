# Tasks: 회원 프로필 diet 카테고리 복수 선택

**Input**: Design documents from `/specs/kb-340-member-diet-categories/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/member-diet-categories.md

**Tests**: Test-First (헌법 원칙 I) — 스토리마다 실패 테스트(Red) 선작성 후 구현(Green).

**Organization**: Foundational = enum 승격·스키마·도메인 모델(두 스토리 공통 전제, ddl-auto=validate 라 엔티티·마이그레이션 동행 필수). US1(온보딩·수정 저장) → US2(조회 복원) 순서 — US2 는 US1 이 저장한 값을 읽는다.

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: DietCategory 승격 + member 스키마·도메인 확장 — 기존 회귀 없음(하위호환)까지 확인.

- [x] T001 [P] Flyway 마이그레이션 — `api/src/main/resources/db/migration/V<생성시각>__member_diet_categories.sql`: `ALTER TABLE member ADD COLUMN diet_categories json NOT NULL DEFAULT (JSON_ARRAY())` (파일명 timestamp 포맷)
- [x] T002 [P] `DietCategory` 승격 — `api/src/main/kotlin/com/kbap/api/ingredient/DietCategory.kt` 를 `common/src/main/kotlin/com/kbap/common/domain/ingredient/model/DietCategory.kt` 로 **이동(내용 무변경)**, 참조 갱신: `api.ingredient` diets 조회 관련 파일·`api/src/test/kotlin/com/kbap/api/ingredient/DietCategoryMappingSyncTest.kt` 의 import
- [x] T003 [P] 에러 코드 추가 — `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt`: `INVALID_DIET_CATEGORY("MEMBER-011", 400, "지원하지 않는 diet 카테고리입니다")` (`ErrorCodeStatusTest` 형식 검증 통과 확인)
- [x] T004 **Red(도메인)**: `common/src/test/kotlin/com/kbap/common/domain/member/model/MemberProfileTest.kt` 에 시나리오 추가 — (1) diet 2종 저장·중복 입력 Set 정규화, (2) 미지원 값 `INVALID_DIET_CATEGORY` 예외, (3) `updatedWith(dietCategories = null)` 유지·`emptyList()` 해제, (4) `empty()` 는 빈 집합. 실행해 **실패(Red) 확인**(컴파일 에러도 Red)
- [x] T005 도메인 확장(Green) — `common/src/main/kotlin/com/kbap/common/domain/member/model/MemberProfile.kt`: `dietCategories: Set<DietCategory>` 필드·`validatedDiets`·`updatedWith` 파라미터·`empty()` 갱신 + `common/src/main/kotlin/com/kbap/common/domain/member/model/Member.kt`: `@JdbcTypeCode(SqlTypes.JSON) var dietCategories: List<String> = emptyList()` 컬럼 필드·`profile` getter·`updateProfile` 반영. `./gradlew :common:test --tests "*MemberProfileTest"` 그린

**Checkpoint**: `./gradlew :api:test --tests "com.kbap.api.member.*" --tests "com.kbap.api.ingredient.*"` 그린 — 기존 회귀 없음(diet 미전송 하위호환·enum 이동 무해) 확인.

---

## Phase 2: User Story 1 — 온보딩·프로필 수정에서 diet 를 복수 선택한다 (P1)

**Goal**: 온보딩(1.0/1.1)·프로필 수정(DTO 2벌)에 `dietCategories` additive 필드 — 누락=유지(수정)/빈 목록(온보딩), 빈 배열=해제, 미지원 값 400 MEMBER-011.

**Independent Test**: 온보딩에 2종 포함 → DB 저장 확인. 수정으로 교체·해제·누락 유지. 미지원 값 400.

- [x] T006 [US1] **Red**: `api/src/test/kotlin/com/kbap/api/member/MemberControllerTest.kt`(또는 온보딩·수정을 다루는 기존 테스트 클래스에 맞춰) 시나리오 추가 — (1) 온보딩에 `dietCategories: ["VEGAN","GLUTEN_FREE"]` → 저장(DB 컬럼 확인), (2) diet 없이 온보딩 → 빈 배열 저장, (3) 수정으로 `["HALAL"]` 교체, (4) 수정에서 필드 누락 → 기존 유지, (5) `[]` → 전체 해제, (6) `["KETO"]` → 400 MEMBER-011, (7) `["VEGAN","VEGAN"]` → 1건 저장. 수정은 1.0·1.1 두 버전 경로 모두(`MemberProfileUpdateVersionTest.kt` 참조). 실행해 **실패(Red) 확인**
- [x] T007 [P] [US1] 온보딩 요청 확장 — `api/src/main/kotlin/com/kbap/api/member/OnboardingRequest.kt`: `dietCategories: List<String> = emptyList()` + `toInput` 전달, `api/src/main/kotlin/com/kbap/api/member/MemberProfileInput.kt`: 필드 추가
- [x] T008 [P] [US1] 수정 요청 확장 — `api/src/main/kotlin/com/kbap/api/member/ProfileUpdateRequest.kt`·`ProfileUpdateNoCountryRequest.kt`(두 DTO 모두): `dietCategories: List<String>? = null` + `api/src/main/kotlin/com/kbap/api/member/ProfileUpdateInput.kt`: 필드 추가
- [x] T009 [US1] 서비스 스레딩(Green) — `api/src/main/kotlin/com/kbap/api/member/MemberService.kt`: `completeOnboarding`·`updateProfile` 에서 input 의 dietCategories 를 `Member.completeOnboarding`/`updateProfile` 로 전달(`Member.kt` 의 이름 있는 파라미터 오버로드에 `dietCategories: List<String>? = null` 추가). swagger `@Schema` 문서(MemberApi/DTO) 포함. **Green 확인**: `./gradlew :api:test --tests "com.kbap.api.member.*"`

**Checkpoint**: 저장 경로 완결(MVP) — DB 에 diet 배열이 남는다.

---

## Phase 3: User Story 2 — 내 프로필에서 선택한 diet 를 복원한다 (P1)

**Goal**: `GET /api/members/me/profile` 응답에 `dietCategories` 배열 — 미선택·기존 회원은 `[]`, 기존 `avoidanceSubstanceCodes` 의미 불변.

**Independent Test**: 저장 후 조회 → 동일 목록. 미선택 회원 → 빈 배열.

- [x] T010 [US2] **Red**: 내 프로필 조회 테스트에 시나리오 추가(`MemberControllerTest.kt`) — (1) 저장한 2종이 응답 `dietCategories` 에 그대로, (2) 미선택 회원 → `[]`, (3) `avoidanceSubstanceCodes` 는 직접 지정분만(기존 의미) 동시 확인. 실행해 실패 확인
- [x] T011 [US2] 응답 확장(Green) — `api/src/main/kotlin/com/kbap/api/member/MyProfileResult.kt`·`MyProfileResponse.kt`: `dietCategories: List<String>` 추가·매핑(+swagger `@Schema`). **Green 확인**: `./gradlew :api:test --tests "com.kbap.api.member.*"`

**Checkpoint**: 저장→복원 왕복 완결.

---

## Phase 4: Polish & Cross-Cutting

- [x] T012 판정 무개입 회귀 — `./gradlew :api:test --tests "com.kbap.api.food.*" --tests "com.kbap.api.scan.*"` (위험도·스캔 경로 무변경 확인)
- [x] T013 전체 빌드 그린 — `./gradlew build` (ArchUnit — enum 이동 후 경계 검사·ddl-auto=validate·OpenAPI 스냅샷 포함). 필요시 quickstart.md 수동 검증

---

## Dependencies

```text
Foundational: T001 ∥ T002 ∥ T003 → T004(Red) → T005(Green) → checkpoint
  → US1: T006(Red) → T007 ∥ T008 → T009(Green)
  → US2: T010(Red) → T011(Green)   # US1 의 저장 경로 사용
  → Polish: T012 → T013
```

- [P]: T001∥T002∥T003(서로 다른 파일·무의존), T007∥T008(온보딩/수정 DTO 분리).
- T004/T005 는 T002(enum 위치)·T003(에러 코드)에 의존.

## Implementation Strategy

- **MVP = Foundational + US1**: 저장까지로 계약 검증 가능, US2 는 반환 필드 추가뿐.
- enum 이동(T002)은 순수 이동 — 내용을 조금이라도 바꾸면 `DietCategoryMappingSyncTest`(기획 번호표 전수 대조)가 잡는다.
- 수정 DTO 2벌(1.0/1.1) 동시 수정은 알려진 함정([[member-currency]] — 필드 추가 시 함께) — T008 이 두 파일을 한 태스크로 묶은 이유.
- 커밋 단위: 단일 feature 커밋(파일 겹침 큼).
