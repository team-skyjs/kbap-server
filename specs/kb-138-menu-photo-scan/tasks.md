# Tasks: 메뉴판 사진 스캔 — 업로드 완료 검증 + 메뉴명·가격 추출

**Input**: Design documents from `/specs/kb-138-menu-photo-scan/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R7), data-model.md, contracts/(images-complete-api·scans-api), quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 I) — 각 스토리의 테스트 task 를 먼저 작성하고 **실패(Red)를 확인한 뒤** 구현한다. 전부 Kotest BehaviorSpec(한국어 given/when/then). 실 S3·실 OpenAI 접근 금지(FR-017) — seam 페이크로 검증.

**Organization**: 유저 스토리별 독립 구현·검증. US1(완료 검증)과 US2(사진 스캔)는 서로 다른 모듈 축이라 Foundational 이후 병렬 가능.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup (모듈 스캐폴드)

**Purpose**: 신규 모듈 2개(`:domain:image`·`:infra:storage`)를 빌드 그래프에 올린다.

- [x] T001 `gradle/libs.versions.toml` 에 AWS SDK for Java v2 `s3` 좌표(버전 포함) 등재, `settings.gradle.kts` 에 `":domain:image"`(도메인 컨테이너 절)·`":infra:storage"`(인프라 절) include 추가
- [x] T002 [P] `domain/image/build.gradle.kts` 생성 — `plugins { id("kbap.domain-conventions") }` 만(모듈 고유 의존 없음, 리프)
- [x] T003 [P] `infra/storage/build.gradle.kts` 생성 — `plugins { id("kbap.spring-conventions") }` + `"implementation"(project(":domain:image"))`(seam 구현) + `"implementation"(libs.aws.s3)`
- [x] T004 `./gradlew build -Dkotest.tags="!arch"` 로 빈 모듈 포함 전체 컴파일 확인

**Checkpoint**: 빌드 그래프에 신규 모듈 2개 등록 완료.

---

## Phase 2: Foundational (전 스토리 공통 선행)

**Purpose**: 스토리들이 공유하는 에러 코드·seam 계약. **이 단계 전까지 스토리 착수 금지.**

- [x] T005 [P] `core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt` 에 5종 추가 — `NOT_IMAGE_FILE("IMAGE-001", 400)`·`UPLOAD_MISMATCH("IMAGE-002", 400)`·`UPLOADED_OBJECT_NOT_FOUND("IMAGE-003", 400)`·`SCAN_IMAGE_NOT_VERIFIED("SCAN-001", 400)`·`MENU_BOARD_RECOGNITION_FAILED("SCAN-002", 503)` (형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 검증 — 통과 확인)
- [x] T006 [P] `core/src/main/kotlin/com/kbap/core/scan/MenuBoardVisionExtractor.kt` 신규 — seam `fun extract(imagePath: String): List<ExtractedMenu>` + `ExtractedMenu(name: String, koreanName: String, priceKrw: Int?)`(init 검증: name/koreanName blank 금지, priceKrw 음수 금지)
- [x] T007 [P] `domain/image/src/main/kotlin/com/kbap/domain/image/StorageObjectStore.kt` 신규 — seam `fun head(path: String): StorageObjectMetadata?`(없으면 null)·`fun delete(path: String)` + `StorageObjectMetadata(contentType: String, sizeBytes: Long)`

**Checkpoint**: 계약 고정 — US1·US2 병렬 착수 가능.

---

## Phase 3: User Story 1 - 업로드 완료 신고와 파일 검증 (Priority: P1) 🎯

**Goal**: `POST /api/v1/images/complete` — HeadObject 대조 검증, 사진만 기록, 실패 시 오브젝트 삭제, 멱등.

**Independent Test**: 페이크 `StorageObjectStore` 로 도메인·MockMvc 테스트 단독 green (`./gradlew :domain:image:test :app:api:test`).

### Tests (먼저 작성 → Red 확인) ⚠️

- [x] T008 [P] [US1] `domain/image/src/test/kotlin/com/kbap/domain/image/ImageUploadServiceTest.kt` 신규 — 페이크 `StorageObjectStore` 로: 이미지(`image/jpeg`)·신고값 일치 → 기록 성공 / 비이미지(`video/mp4`) → delete 호출 + `IMAGE-001` / Content-Type·크기 불일치 → delete + `IMAGE-002` / head null → `IMAGE-003` / 같은 path 재신고 → 재검증 없이 성공(멱등, head 미호출) / 타인 소유 prefix path → 거절 / `getVerifiedImage` — 기록 존재·본인 소유만 반환. **작성 직후 실행해 Red 확인**
- [x] T009 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/image/ImageControllerTest.kt` 신규 — MockMvc + `@TestConfiguration` 페이크 `StorageObjectStore` 빈(실 S3 무접근), 인증은 기존 `TokenIssuer` 헬퍼 재사용: 성공 200 `payload.path` / 비이미지 400 `code=IMAGE-001` / 미존재 400 `IMAGE-003` / `http://` 시작 path 400 `COMMON-002` / 무토큰 401. **Red 확인**

### Implementation

- [x] T010 [P] [US1] `domain/image/src/main/kotlin/com/kbap/domain/image/model/UploadedImage.kt` 신규 — `BaseEntity` 상속, `memberId`·`path`(object_path)·`contentType`·`sizeBytes`, `isOwnedBy(memberId)` 도메인 메서드, MySQL 기준 `@Column(length)` 명시(data-model.md 표)
- [x] T011 [US1] `UploadedImageJpaRepository.kt`(**internal**, `findByPath`) + `ImageUploadService.kt` 신규 — `completeUpload(memberId, path, declaredContentType, declaredSize)`: path 소유 prefix 대조 → 멱등 단락 → head(외부, 무트랜잭션) → `image/*`·신고값 판정 → 성공 시 기록(짧은 쓰기 tx)·실패 시 delete 후 `BusinessException` / `getVerifiedImage(memberId, path)`(읽기 tx) — R5·R7 준수, T008 green
- [x] T012 [US1] Flyway `app/api/src/main/resources/db/migration/V<생성시각 timestamp>__create_uploaded_image_table.sql` 신규 — `uploaded_image`(data-model.md: UNIQUE object_path·INDEX member_id·FK member, BaseEntity 공통 컬럼)
- [x] T013 [US1] `app/api/src/main/kotlin/com/kbap/app/api/image/` 신규 — `ImageApi.kt`(swagger only — 삭제·멱등 명시), `ImageController.kt`(`ApiPaths.V1 + "/images"`, `@PostMapping("/complete")`, `@AuthMemberId`), `ImageCompleteRequest.kt`(path blank·전체 URL·길이 검증, contentType·size 필수)·`ImageCompleteResponse.kt` + `app/api/build.gradle.kts` 에 `"implementation"(project(":domain:image"))` 추가 — T009 green
- [x] T014 [US1] `infra/storage/src/main/kotlin/com/kbap/infra/storage/S3StorageObjectStore.kt` 신규(AWS SDK v2 HeadObject/DeleteObject — NoSuchKey → null 매핑, 얇은 어댑터라 단위 테스트 없음) + `app/api/src/main/kotlin/com/kbap/app/api/config/StorageConfig.kt` 신규(`@ConditionalOnProperty("kbap.storage.enabled")` — `S3Client`·빈 조립, `kbap.storage.{bucket,region}`) + `app/api/build.gradle.kts` 에 `"implementation"(project(":infra:storage"))`

**Checkpoint**: US1 단독 green — 완료 신고·검증·삭제·멱등 end-to-end(페이크 스토리지).

---

## Phase 4: User Story 2 - 사진으로 메뉴 스캔: 추출과 위험도 판정 (Priority: P1)

**Goal**: `POST /api/v1/scans` 요청을 `{imagePath}` 로 교체, vision 추출(가격 축약 복원) → 기존 food 매칭·위험도 판정, 응답 additive(`price` 추가).

**Independent Test**: 페이크 `MenuBoardVisionExtractor`·`ImageUploadService` 경유로 `./gradlew :domain:scan:test :infra:llm:test :app:api:test` 단독 green.

### Tests (먼저 작성 → Red 확인) ⚠️

- [x] T015 [P] [US2] `infra/llm/src/test/kotlin/com/kbap/infra/llm/menu/MenuBoardResultParserTest.kt` 신규 — 스파이크 JSON 포맷(`{"results":[{idx,name,koreanName,price}]}`) 정상 파싱 / price null / results 빈 배열 / results 키 부재·비 JSON → 파싱 예외(조용한 빈 결과 금지). **Red 확인**
- [x] T016 [P] [US2] `domain/scan/src/test/kotlin/com/kbap/domain/scan/ScanServiceTest.kt` 재작성 — 페이크 extractor·페이크 image 창구로 `scanMenuBoardImage`: 매칭 항목 riskLevel·foodId·price / 미매칭 항목 vision name·koreanName 채움·UNKNOWN / 확정 미등록 메뉴 조사 대기 등록(기존 동작 유지) / 미검증·타인 소유 path → `SCAN-001` / extractor 예외 → `SCAN-002` / 추출 0개 → 빈 results 정상 / 스캔 횟수 증가. **Red 확인**
- [x] T017 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt` 재작성 — MockMvc + `@TestConfiguration` 페이크 extractor·`StorageObjectStore` 빈: `{imagePath}` 요청 → 기존 응답 구조 + `price`(FR-010a 필드 전수 검증) / 미검증 path 400 `SCAN-001` / 전체 URL 400 / blank 400 / 무토큰 401. **Red 확인**

### Implementation

- [x] T018 [P] [US2] `domain/scan/src/main/kotlin/com/kbap/domain/scan/dto/ScanResult.kt` 수정 — `ItemRiskResult` 에 `price: Int?` 추가, 미매칭 항목도 name/koreanName 보유(nullable 유지 — 기존 필드 계약 불변)
- [x] T019 [US2] `domain/scan/src/main/kotlin/com/kbap/domain/scan/ScanService.kt` 수정 — `scanMenuBoardImage(memberId, imagePath)` 신규: `ImageUploadService.getVerifiedImage` 검증(`SCAN-001`) → `MenuBoardVisionExtractor.extract`(**무트랜잭션**, 실패 `SCAN-002`) → koreanName 으로 기존 food 매칭 로직 재사용(vision 확정이므로 `confirmedByInterpreter=true` 경로) → 응답 조립(idx=추출 순번). `assessMenuBoard`·refinement(`ScannedNameInterpreter` 호출)·`ScanInput`/`ScanItemInput` 제거. `domain/scan/build.gradle.kts` 에 `"api"(project(":domain:image"))` 추가 — T016 green
- [x] T020 [US2] `infra/llm` 수정 — `menu/MenuBoardResultParser.kt` 신규(T015 green), `menu/OpenAiMenuBoardVisionExtractor.kt` 신규(스파이크 시스템 프롬프트 이식 — JSON 포맷·천원 축약 복원·비메뉴 제외·사이즈 분리, Spring AI `Media`(URI: `image-base-url`+path 조합)·`responseFormat`(JSON)·temperature 0), `config/LlmModelProperties.kt` 에 `kbap.llm.vision.{enabled,api-key,model,image-base-url}` 그룹 추가, `config/LlmConfiguration.kt` 에 `@ConditionalOnProperty("kbap.llm.vision.enabled")` extractor 빈 추가
- [x] T021 [US2] `app/api/src/main/kotlin/com/kbap/app/api/scan/` 수정 — `ScanRequest.kt` 를 `{imagePath}` 로 교체(blank·전체 URL·길이 검증), `ScanResponse.kt` 에 `price` 추가·swagger 스키마(idx 의미 재정의·"미매칭도 이름 채움") 갱신, `ScanApi.kt` 문서(SCAN-001/002) 갱신 — T017 green

**Checkpoint**: US1+US2 로 업로드→검증→스캔 end-to-end 성립(페이크 외부 시스템). 기존 OCR 텍스트 계약 제거 완료.

---

## Phase 5: User Story 3 - 스캔 내역 히스토리 (Priority: P2)

**Goal**: 전 추출 항목을 회원·이미지 path·메뉴(표기/표준)·가격과 함께 기록 — 가격은 히스토리에만.

**Independent Test**: `./gradlew :domain:scan:test` — 히스토리 시나리오 단독 green.

### Tests (먼저 작성 → Red 확인) ⚠️

- [x] T022 [US3] `ScanServiceTest.kt` 에 히스토리 시나리오 추가 — 스캔 성공 시 **전 항목**(미매칭 포함) 기록: memberId·imagePath·menuName·koreanName·price, 미매칭 row 는 foodId null / `findRecentReadyFoodIds` 가 foodId null row 를 제외. **Red 확인**

### Implementation

- [x] T023 [P] [US3] `domain/scan/src/main/kotlin/com/kbap/domain/scan/model/ScanHistory.kt` 수정 — `foodId: Long?` 완화, `imagePath`·`menuName`·`koreanName`·`price: Int?` 추가(`@Column(length)` — data-model.md 표), `record(...)` 팩토리 확장
- [x] T024 [P] [US3] Flyway `V<생성시각 timestamp>__extend_scan_history_for_photo_scan.sql` 신규 — image_path·menu_name·korean_name·price 컬럼 추가(기존 row 기본값 전략 — data-model.md), `food_id` NULL 허용 변경(FK 유지)
- [x] T025 [US3] `ScanService.recordHistory` 확장(전 추출 항목 saveAll) + `ScanHistoryJpaRepository.findRecentReadyFoodIds` 에 `food_id IS NOT NULL` 조건 — T022 green

**Checkpoint**: 전 스토리 독립 green.

---

## Phase 6: Polish & Cross-Cutting

- [x] T026 `./gradlew build` 전체 통과 — ArchUnit(`ModuleBoundaryTest` — 신규 모듈 경계·`@Entity` 위치·컨트롤러 `/api/v` 규칙)·`ErrorCodeStatusTest` 포함. 미사용이 된 `ScannedNameInterpreter`/`UpstageScannedNameInterpreter` 잔존 소비자 확인 — 스캔 외 소비자가 없으면 제거는 사용자 확인 후 별도(이번 범위: 스캔 경로 미사용까지)
- [x] T027 [P] quickstart.md 기준 점검 — `kbap.llm.vision.*`·`kbap.storage.*` 프로퍼티가 local(미구성 부팅 안전)·dev/prod 프로파일 문서와 일치하는지, Swagger UI 에서 두 API 문서(멱등·삭제·idx 의미·price) 노출 확인
- [x] T028 [P] `specs/kb-138-menu-photo-scan/` 기준 스펙 FR 전수 대조(특히 FR-010a additive·FR-014 가격 저장 위치) 후 잔여 불일치 기록

---

## 구현 노트 (계획 대비 조정)

- **StorageObjectStore seam → `:core`**(계획 R2 의 `:domain:image` 에서 이동). `ScannedNameInterpreter` 선례와 동일(외부 seam 은 `:core`), 그리고 KB-145 워크트리의 `:infra:storage` 가 이미 `:core` 를 의존해 **머지 충돌 0**. AWS 카탈로그 항목·`settings.gradle.kts` storage include 라인은 KB-145 텍스트를 그대로 복사.
- **소유 검증은 기록 기반**: `completeUpload` 가 호출자를 소유자로 저장하고 `findVerifiedImage` 가 대조. KB-145 의 객체 키 prefix 포맷(미머지)에 결합하지 않음.
- **도메인 단위 테스트 통합**(T008·T016·T022): 외부 seam 을 주입받는 도메인 서비스(`ImageUploadService`·`ScanService`)는 기존 `ScanTestApp` 주석 관례대로 **app:api MockMvc 통합 테스트**(페이크 seam)로 검증 — 별도 도메인 부트 테스트 미신설. 파서만 `:infra:llm` 단위 테스트.
- **`findRecentReadyFoodIds` 쿼리 무변경**: `INNER JOIN food` 가 이미 null food_id 를 제외하므로 `IS NOT NULL` 불필요.
- **응답 name/koreanName**: `name`=사진 표기 그대로(비전 원문), `koreanName`=매칭 시 음식 표준명·미매칭 시 비전 표준명(FR-007 충족, 기존 필드 재의미).
- **추가 수정(테스트가 잡은 통합 누락)**: `WebMvcAuthConfig` 에 `/api/v1/images`·`/images/*` 를 JWT 필터에 등록(신규 인증 엔드포인트).
- **부팅 설정**: `application.yml` 에 `kbap.llm.vision`·`kbap.storage` 추가(둘 다 기본 on, 키/버킷 미설정 시 fail-fast) — 두 빈은 `ScanService`·`ImageUploadService` 의 하드 의존이라 upstage 와 동일 철학. 테스트는 `src/test` yml 이 shadow 해 페이크 사용(회귀 0).
- **미사용 유휴**: `ScannedNameInterpreter`/`Upstage*`(스캔 경로에서 제거됨) 의 seam·구현 제거는 다른 소비자 확인 후 별도 — 이번 범위는 "스캔 경로 미사용"까지.
- **검증 한계**: 실 S3·실 OpenAI 키가 없어 vision/storage **실 호출 스모크 테스트는 미실행**. 빈 생성 경로는 컴파일·기존 `openAiChatModel` 빌더 재사용으로 확인, S3Client 는 자격증명 지연 로드로 부팅 안전.

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 → Phase 2 → (Phase 3 ∥ Phase 4) → Phase 5 → Phase 6**
- US1(Phase 3)과 US2(Phase 4)는 Foundational 이후 **병렬 가능** — 단 US2 의 T019 는 US1 의 `ImageUploadService`(T011) 창구가 필요하므로, 병렬 진행 시 T011 완료 후 T019 착수
- US3(Phase 5)은 US2 의 `scanMenuBoardImage`(T019) 에 기록을 얹으므로 US2 이후

### Within Each Story

- 테스트(Red 확인) → 모델 → 서비스 → 컨트롤러/어댑터 순. 같은 파일을 만지는 task 는 [P] 없음

### Parallel Opportunities

- Phase 1: T002 ∥ T003 · Phase 2: T005 ∥ T006 ∥ T007
- US1 테스트 T008 ∥ T009, US2 테스트 T015 ∥ T016 ∥ T017 (서로 다른 모듈)
- T010(엔티티) ∥ T009, T018 ∥ T015, T023 ∥ T024

## Parallel Example: Phase 2 + US1 착수

```bash
# Foundational 동시 진행 (서로 다른 파일):
Task: "T005 ErrorCode 5종 추가"
Task: "T006 :core MenuBoardVisionExtractor seam"
Task: "T007 :domain:image StorageObjectStore seam"

# US1 테스트 동시 작성 (Red 확인까지):
Task: "T008 ImageUploadServiceTest — 페이크 스토리지"
Task: "T009 ImageControllerTest — MockMvc"
```

## Implementation Strategy

- **MVP**: Phase 1~4 (US1+US2) — 사용자 가치(사진 → 위험도·가격)는 두 P1 스토리가 함께 만든다. US1 만으로도 "검증된 업로드" 단독 배포 가치가 있으나 데모는 US2 까지.
- **Incremental**: US1 checkpoint 에서 완료 API 단독 검증 → US2 에서 스캔 교체 → US3 히스토리 → Polish.
- 각 task(또는 논리 묶음) 완료 시 커밋(Development Workflow). 구현은 `tdd-harness-orchestrator` 로 test-writer → implementer → (code-reviewer ∥ database-expert) 사이클 권장.
