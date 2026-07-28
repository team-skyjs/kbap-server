# Tasks: 음식 이미지·스캔 이미지 저장 키 규약 정비

**Input**: Design documents from `/specs/kb-243-food-image-key/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 각 스토리는 실패 테스트(Red) → 최소 구현(Green) → 리팩터 순서.

**Organization**: 스토리 2개는 서로 다른 파일을 만져 완전히 독립 — 순서 무관, 병렬 가능.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 모듈·기존 테스트 파일 수정만 있는 변경. 신규 의존성·스캐폴드 불요.

## Phase 2: Foundational

없음 — 스토리 간 공유 선행 작업 없음.

---

## Phase 3: User Story 1 - 배치 생성 음식 이미지의 파일명 규약 일치 (Priority: P1) 🎯 MVP

**Goal**: 배치 회수 저장 키를 `images/food/{sha256(음식명)[:12]}_{uuid16}.png` 로 바꿔 재생성 캐시 문제 해소 (FR-001~005)

**Independent Test**: `./gradlew :api:test --tests "com.kbap.api.food.FoodImageBatchCollectServiceTest"` — 키 형식·재생성 신규 키·동명 음식 비충돌이 통과하면 완결.

- [X] T001 [US1] (Red) `api/src/test/kotlin/com/kbap/api/food/FoodImageBatchCollectServiceTest.kt` 수정: ① 기존 기대 키 `images/food/{id}.png` 를 정규식 `^images/food/[0-9a-f]{12}_[0-9a-f]{16}\.png$` 매칭으로 교체(회수 성공 시 `Food.imageRef`·`ImageBatchItem.fileName` 동일 키), ② 같은 음식 2회 회수 시 서로 다른 키 생성(FR-003), ③ 동명 음식 2건이 한 배치에 있어도 키 비충돌(uuid 상이), ④ 파일명 규칙 고정 단위 검증 — `storageKeyOf("불고기")` 가 해시 12자리(소문자 hex)·`_`·uuid 16자리·`.png`·환경접두 없음(FR-002·005), ⑤ 회수 시점 음식 삭제 시 put 없이 fail 처리 유지. 실행해 **Red 확인**.
- [X] T002 [US1] (Green) `api/src/main/kotlin/com/kbap/api/food/FoodImageBatchCollectService.kt` 구현: `storageKeyOf(foodName: String)` — `images/food/` + sha256(UTF-8) hex 앞 12자리 + `_` + `UUID.randomUUID()` hex(하이픈 제거) 앞 16자리 + `.png`. `handleResult` 는 S3 put **전에** `foodRepository.findById(item.foodId)` 로 음식명 확보(없으면 put 없이 해당 항목 fail), put 후 트랜잭션(재조회·attachImage·done)은 현행 유지. T001 테스트 **Green 확인**.
- [X] T003 [US1] `./gradlew :api:test` 회귀 실행 — `images/food/` 리터럴을 픽스처로 쓰는 기존 테스트(FoodJpaRepositoryTest 등)는 데이터 문자열일 뿐이므로 실패 시에만 조정. 통과 후 논리 단위 커밋.

**Checkpoint**: US1 단독 배포 가능 — 스캔 키는 기존 형식 그대로 동작.

---

## Phase 4: User Story 2 - 메뉴판 스캔 업로드 키 구조 개편 (Priority: P2)

**Goal**: 발급 키를 `{환경접두}/images/scans/{yyyy}/{mm}/{memberId}_{uuid}.{ext}` 로 개편 (FR-006)

**Independent Test**: `./gradlew :api:test --tests "com.kbap.api.image.PresignedUploadServiceTest"` — 발급 키가 새 규약과 일치하면 완결.

- [ ] T004 [P] [US2] (Red) `api/src/test/kotlin/com/kbap/api/image/PresignedUploadServiceTest.kt` 수정: 스캔 발급 키 기대 정규식을 `^images/scans/\d{4}/\d{2}/1024_[0-9a-f-]{36}\.jpg$` 로, 환경접두 케이스는 `^dev/images/scans/...` 로, 프로필 케이스는 `^images/profile/\d{4}/\d{2}/7_[0-9a-f-]{36}\.jpg$` 로 교체(폴더명은 profile 유지 — research R5). 실행해 **Red 확인**.
- [ ] T005 [US2] (Green) `api/src/main/kotlin/com/kbap/api/image/UploadPurpose.kt` 의 `MENU_SCAN("scan")` → `MENU_SCAN("scans")`, `api/src/main/kotlin/com/kbap/api/image/PresignedUploadService.kt` 의 포맷 `images/%s/%04d/%02d/%d/%s.%s` → `images/%s/%04d/%02d/%d_%s.%s`. T004 테스트 **Green 확인**.
- [ ] T006 [US2] `./gradlew :api:test` 회귀 실행(MenuScanScenarioTest·ImageControllerTest 포함 — 경로 문자열 비의존이라 통과 예상). 통과 후 논리 단위 커밋.

---

## Phase 5: Polish & Cross-Cutting

- [ ] T007 [P] `api/src/main/kotlin/com/kbap/api/scan/ScanApi.kt` swagger 예시 `"test/images/scan/한식마당.jpg"` 를 새 규약 예시(`"dev/images/scans/2026/07/1_550e8400-e29b-41d4-a716-446655440000.jpg"`)로 갱신 — 문서 정합.
- [ ] T008 `./gradlew build` 전체 빌드(아치 테스트 포함) 통과 확인 후 최종 커밋.

## Dependencies

- US1(T001→T002→T003)과 US2(T004→T005→T006)는 **파일이 겹치지 않아 상호 독립** — 어느 쪽을 먼저 해도 되고 병렬 가능.
- T007 은 언제든 가능(문서 문자열), T008 은 모든 구현 완료 후.

## Parallel Example

- T001 과 T004 를 동시에 작성(서로 다른 테스트 파일) → 각자 Red 확인 → T002 와 T005 동시 구현.

## Implementation Strategy

- **MVP = US1** (Jira KB-243 의 결함 해소). US2 는 구조 정비라 별도 커밋으로 이어 배포.
- 스토리별 커밋 1개 이상(작업/논리 단위 커밋 — 헌법 Development Workflow).
