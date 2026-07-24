# Tasks: 이미지 업로드 객체 키 환경 접두(key-prefix) 지원

**Input**: Design documents from `/specs/kb-171-storage-key-prefix/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md (data-model.md·contracts/ 없음 — 엔티티·API 계약 무변경)

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). US1 의 Red 테스트가 유일한 신규 테스트 표면이며 반드시 구현보다 먼저 작성·실패 확인한다.

**Organization**: 사용자 스토리별 그룹. 단, 본 기능은 프로덕션 3파일 + yml 3파일의 소형 변경이라 US1 이 코어 TDD 사이클 전체를 소유하고, US2 는 회귀 무변경 검증, US3 은 설정 선언이다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup / Phase 2: Foundational

**없음** — 신규 모듈·의존성·인프라 0. 기존 KB-145 업로드 경로를 그대로 수정한다.

---

## Phase 3: User Story 1 - 환경별 업로드 폴더 분리 (Priority: P1) 🎯 MVP

**Goal**: 접두가 설정된 환경에서 발급되는 업로드 객체 키가 `{접두}/images/…` 로 생성된다(슬래시 정규화 포함).

**Independent Test**: `ImageUploadApplicationServiceTest` 에서 `keyPrefix = "dev"` 프로퍼티로 발급한 키가 `^dev/images/…` 정규식에 매칭되면 완결.

### Tests for User Story 1 (Test-First — 먼저 작성, Red 확인) ⚠️

- [X] T001 [US1] `application/src/test/kotlin/com/kbap/application/upload/ImageUploadApplicationServiceTest.kt` 에 접두 시나리오 추가 — `properties()` 헬퍼에 `keyPrefix: String = ""` 파라미터를 더하고(`ImageUploadProperties(keyPrefix = …)` 전달), 신규 `when` 3개: (1) `keyPrefix = "dev"` → 키가 `^dev/images/scan/…` 매칭, (2) `keyPrefix = "dev/"` 와 `"/dev"` → 동일하게 `^dev/images/…` (중복 슬래시 `dev//` 부재 단정), (3) `publicUrl` 이 접두 포함 키로 조립됨(`https://cdn.test/dev/images/…`). 실행해 **컴파일 실패(keyPrefix 필드 부재) = Red 확인**: `./gradlew :application:test --tests "com.kbap.application.upload.ImageUploadApplicationServiceTest"`

### Implementation for User Story 1

- [X] T002 [US1] `application/src/main/kotlin/com/kbap/application/upload/ImageUploadProperties.kt` 에 `keyPrefix: String` 필드 추가(기본값 없음 — 컴파일러가 조립 지점 갱신을 강제)
- [X] T003 [US1] `application/src/main/kotlin/com/kbap/application/upload/ImageUploadApplicationService.kt` 의 `objectKey()` 에 접두 결합 — `properties.keyPrefix.trim('/')` 후 빈 값이면 기존 키 그대로, 아니면 `"$prefix/$key"`. T001 테스트 실행해 **Green 확인**
- [X] T004 [US1] `app/api/src/main/kotlin/com/kbap/app/api/config/ImageUploadConfig.kt` 에 `@Value("\${kbap.storage.key-prefix:}") keyPrefix: String` 주입 추가 + `app/api/src/main/resources/application.yml` 의 `kbap.storage` 에 `key-prefix: ${STORAGE_KEY_PREFIX:}` 선언 — `./gradlew :app:api:compileKotlin` 통과 확인

**Checkpoint**: 접두 설정 시 키 접두 결합이 단위 테스트로 완결 검증됨.

---

## Phase 4: User Story 2 - prod 기존 키 구조 무변경 (Priority: P1)

**Goal**: 접두 미설정(빈 값)이면 기존 키 구조가 그대로 유지된다 — prod·local 회귀 0.

**Independent Test**: 기존 테스트(`^images/scan/…`·`^images/profile/…` 정규식 단정)가 **무수정으로** 통과하면 완결 — `properties()` 기본 `keyPrefix = ""` 가 곧 빈 접두 시나리오다.

### Tests for User Story 2

- [X] T005 [US2] 신규 테스트 없음 — 기존 시나리오가 빈 접두 검증 그 자체. `./gradlew :application:test :app:api:test` 로 기존 테스트(발급 규격·컨트롤러·시나리오) 전부 무수정 통과 확인 (SC-002·SC-005)

**Checkpoint**: 빈 접두 = 기존 동작 보존이 기존 테스트로 증명됨.

---

## Phase 5: User Story 3 - 운영자의 접두 무배포 변경 (Priority: P2)

**Goal**: dev·staging 프로필 설정에 접두 항목이 선언되어 env `STORAGE_KEY_PREFIX` 만으로 값 지정·변경이 가능하다(미설정 시 빈 값 — 기동 실패 없음).

**Independent Test**: dev·staging yml 의 `kbap.storage.key-prefix` 선언이 `${STORAGE_KEY_PREFIX:}` 형태인지 확인(값 하드코딩 금지). 런타임 검증은 배포 후 quickstart §3 런북.

### Implementation for User Story 3

- [X] T006 [P] [US3] `app/api/src/main/resources/application-dev.yml` 의 `kbap.storage` 에 `key-prefix: ${STORAGE_KEY_PREFIX:}` 선언(기존 bucket·public-base-url 주석 관례에 맞춰 접두 용도 주석 1줄)
- [X] T007 [P] [US3] `app/api/src/main/resources/application-staging.yml` 에 동일 선언

**Checkpoint**: 전 스토리 완결 — 접두는 env 로만 제어되고 커밋 없이 반전 가능.

---

## Phase 6: Polish & Cross-Cutting

- [X] T008 전체 테스트 실행 `./gradlew test` — 전 모듈 무수정 통과 확인(quickstart §1, SC-005). 통과 후 논리 단위 커밋

---

## Dependencies & Execution Order

- **T001 → T002 → T003** — Red 확인 후에만 구현(원칙 I). T002·T003 은 같은 TDD 사이클의 순차 단계.
- **T003 → T004** — Properties 필드가 생겨야 config 주입 컴파일 가능.
- **T005** — T003 이후 언제든(회귀 검증).
- **T006·T007 [P]** — T004(base 선언) 이후 병렬 가능(서로 다른 yml).
- **T008** — 마지막.

### Parallel Opportunities

T006 ∥ T007 (프로필 yml 2개) — 그 외는 단일 파일 체인이라 순차가 자연스럽다.

## Implementation Strategy

MVP = Phase 3(US1)까지 — 접두 결합 로직·배선 완결. US2 는 검증만, US3 은 yml 2줄. 전체가 30분 내 단일 사이클로 끝나는 규모이므로 T001→T008 순차 진행을 권장한다.
