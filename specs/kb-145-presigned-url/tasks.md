# Tasks: 이미지 업로드용 presigned URL 발급 API (KB-145)

**Input**: Design documents from `specs/kb-145-presigned-url/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/upload-url.md

**Tests**: Test-First(헌법 I, NON-NEGOTIABLE) — 각 스토리는 구현 전 **실패 테스트를 먼저** 작성(Red → Green → Refactor). 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 스토리별 그룹. 두 스토리 모두 P1(함께 MVP). US1=발급 엔드포인트(앱 계층, 페이크 port 로 S3 없이 검증) · US2=실 S3 어댑터(실 서명 업로드 URL + 안정 공개 URL). 서로 독립 테스트 가능.

**Path 기준**: 멀티모듈 — `core/`, `application/`, `infra/storage/`, `app/api/`. 패키지 `com.kbap.*`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 신규 모듈 `:infra:storage` 등록 + AWS SDK 의존성 배선.

- [ ] T001 [P] `gradle/libs.versions.toml` 에 AWS SDK v2 추가 — `[versions] aws-sdk`(최신 2.x, 예 2.31.x 확인) + `[libraries] aws-bom`(software.amazon.awssdk:bom, version.ref=aws-sdk)·`aws-s3`(software.amazon.awssdk:s3, 버전 생략)·`aws-s3-presigner`(software.amazon.awssdk:s3-presigner, 버전 생략).
- [ ] T002 `settings.gradle.kts` 의 `include(...)` 인프라 구획에 `":infra:storage"` 추가(주석: S3 presigned URL 발급 어댑터).
- [ ] T003 [P] `infra/storage/build.gradle.kts` 생성 — `plugins { id("kbap.spring-conventions") }` + `"implementation"(platform(libs.aws.bom))`·`"implementation"(libs.aws.s3)`·`"implementation"(libs.aws.s3.presigner)`·`"implementation"(project(":application"))`·`"implementation"(project(":core"))`·`"implementation"(libs.spring.context)`·`"implementation"(libs.slf4j.api)`.
- [ ] T004 `app/api/build.gradle.kts` 에 `"runtimeOnly"(project(":infra:storage"))` 추가(주석: S3 presign 어댑터 런타임 조립 — infra:llm 방식). AWS SDK 타입이 web 컴파일 클래스패스에 새지 않게 runtimeOnly.

**Checkpoint**: `./gradlew :infra:storage:help` 로 신규 모듈이 인식되는지 확인.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 두 스토리가 공유하는 타입·에러코드·seam. 이 단계 없이는 어떤 스토리도 시작 불가.

**⚠️ CRITICAL**: 이 단계 완료 후에만 US1/US2 착수.

- [ ] T005 [P] `core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt` 에 신규 접두 `UPLOAD` 추가 — `UNSUPPORTED_IMAGE_CONTENT_TYPE("UPLOAD-001", 400, ...)`·`UNSUPPORTED_UPLOAD_PURPOSE("UPLOAD-002", 400, ...)`·`IMAGE_TOO_LARGE("UPLOAD-003", 400, ...)`.
- [ ] T006 [P] `application/src/main/kotlin/com/kbap/application/upload/dto/UploadPurpose.kt` — enum `MENU_SCAN`(초기 지원). (REVIEW 는 리뷰 태스크에서 활성 — 지금 미추가 또는 미지원 처리.)
- [ ] T007 [US1] `application/src/main/kotlin/com/kbap/application/upload/dto/PresignUploadCommand.kt`(memberId·purpose·contentType·contentLength) + `PresignedUpload.kt`(uploadUrl·requiredHeaders·publicUrl·objectKey·expiresAt) 생성. (T006 의존 — purpose 타입 참조.)
- [ ] T008 [US1] `application/src/main/kotlin/com/kbap/application/upload/PresignedUploadPort.kt` — seam 인터페이스 `fun issue(command: PresignUploadCommand, key: String, ttl: Duration): PresignedUpload`. (T007 의존.)
- [ ] T009 [P] `application/src/main/kotlin/com/kbap/application/upload/ImageUploadProperties.kt` — 정책값(allowedContentTypes: Set<String>·maxBytes: Long·uploadTtl: Duration·publicBaseUrl: String·purposePrefixes: Map<UploadPurpose,String>).

**Checkpoint**: `./gradlew :core:compileKotlin :application:compileKotlin` 통과. 공유 계약 준비됨.

---

## Phase 3: User Story 1 - 인증 사용자가 업로드용 서명 URL을 발급받는다 (Priority: P1) 🎯 MVP

**Goal**: `POST /api/v1/images/upload-url` 엔드포인트 — 인증·용도·Content-Type·크기 검증 후 업로드 URL(+객체 키·만료·헤더)을 발급. 앱 계층은 페이크 port 로 S3 없이 완결.

**Independent Test**: MockMvc + 페이크 port 빈으로 401(토큰 없음)·400(형식/용도/초과)·200(happy) 검증. 실 S3 불필요.

### Tests for User Story 1 (Test-First — 먼저 작성, 반드시 FAIL) ⚠️

- [ ] T010 [P] [US1] `application/src/test/kotlin/com/kbap/application/upload/ImageUploadApplicationServiceTest.kt` — 페이크 `PresignedUploadPort`(command·key·ttl 기록)로: (a) 미지원 purpose → `UPLOAD-002`, (b) 미허용 contentType → `UPLOAD-001`, (c) contentLength>maxBytes → `UPLOAD-003`, (d) 객체 키 포맷 `{prefix}/{yyyy}/{MM}/{dd}/{memberId}/{UUID}.{ext}` 및 **연속 2회 발급 키 상이(UUID 유일성)**, (e) port 위임 command 정확성·결과 매핑. **FAIL 확인.**
- [ ] T011 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/upload/ImageUploadControllerTest.kt` (BehaviorSpec + SpringExtension + `@AutoConfigureMockMvc`) + 같은 소스셋에 `FakePresignedUploadPortConfig`(테스트용 `PresignedUploadPort` 빈, scan 의 `UnavailableScannedNameInterpreterConfig` 선례) 작성 — 토큰 없음 401, 미허용 contentType/purpose/초과 400(code 검증), 유효 요청 200 + 봉투(`success=true`, payload.uploadUrl·publicUrl·requiredHeaders). **FAIL 확인.**

### Implementation for User Story 1

- [ ] T012 [US1] `application/src/main/kotlin/com/kbap/application/upload/ImageUploadApplicationService.kt` — `@Service`: 검증(purpose→UPLOAD-002, contentType→UPLOAD-001, size→UPLOAD-003) → 확장자 매핑 → 객체 키 생성(`UUID.randomUUID()`·`Instant`/`LocalDate` 날짜) → `PresignUploadCommand` 구성 → `port.issue(...)` 위임 → 결과 반환. (T010 Green.)
- [ ] T013 [P] [US1] `application/src/main/kotlin/com/kbap/application/upload/UnavailablePresignedUploadPort.kt` — fallback `object`/class: `issue(...)` 호출 시 `BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)`(스토리지 미구성 운영 오류).
- [ ] T014 [P] [US1] `app/api/src/main/kotlin/com/kbap/app/api/upload/UploadUrlRequest.kt`(purpose: String·contentType: String·contentLength: Long + `@field:NotBlank`/`@field:Positive`, `toCommand(memberId)` 변환 — 미지원 purpose 문자열도 여기서 UPLOAD-002 로 수렴) + `UploadUrlResponse.kt`(`from(PresignedUpload)` — uploadUrl·method="PUT"·requiredHeaders·publicUrl·objectKey·expiresAt).
- [ ] T015 [US1] `app/api/src/main/kotlin/com/kbap/app/api/upload/ImageUploadApi.kt`(swagger `@Tag`·`@Operation`·`@ApiResponses` 200/400/401·`@SecurityRequirement("bearerAuth")` — "읽기는 공개/CDN, 비회원 불가" 명시) + `ImageUploadController.kt`(`@RestController @RequestMapping(ApiPaths.V1 + "/images")`, `@PostMapping("/upload-url")`, `@AuthMemberId memberId: Long`, `@Valid @RequestBody`, 반환 `ResponseEntity<BaseResponse<UploadUrlResponse>>`). (T012·T014 의존.)
- [ ] T016 [US1] `app/api/src/main/kotlin/com/kbap/app/api/config/StorageConfig.kt` — `@Configuration`: `ImageUploadProperties` 빈(`@Value`/`@ConfigurationProperties` 로 `kbap.upload.*`·`kbap.storage.public-base-url` 바인딩) + `@Bean @ConditionalOnMissingBean PresignedUploadPort = UnavailablePresignedUploadPort`(실 port 부재 시 부팅 안전). `app/api/src/test/resources/application.yml` 에 `kbap.upload.*`(allowed-content-types·max-bytes·upload-ttl)·`kbap.storage.public-base-url` 테스트값 추가. (T013·T009 의존.)

**Checkpoint**: `./gradlew :application:test :app:api:test --tests "*ImageUpload*"` Green. 엔드포인트가 페이크 port 로 독립 동작.

---

## Phase 4: User Story 2 - 업로드 이미지를 안정 공개 URL로 저장·표시·소비 (Priority: P1)

**Goal**: 실 S3 어댑터가 **실제 서명된 업로드 PUT URL** + **만료 없는 안정 공개 URL**(`publicBaseUrl/key`)을 생성. `kbap.storage.bucket` 설정 시 실 port 조립.

**Independent Test**: `S3PresignedUploadPort` 를 더미 정적 자격증명으로 직접 호출(로컬 서명, 네트워크 없음) → uploadUrl 이 key 에 대한 presigned PUT(host·key·X-Amz-Signature 포함), publicUrl == `publicBaseUrl + "/" + key`, requiredHeaders 에 Content-Type·Content-Length, expiresAt = now+ttl 근사.

### Tests for User Story 2 (Test-First — 먼저 작성, 반드시 FAIL) ⚠️

- [ ] T017 [P] [US2] `infra/storage/src/test/kotlin/com/kbap/infra/storage/S3PresignedUploadPortTest.kt` (BehaviorSpec) — `S3Presigner` 를 `StaticCredentialsProvider`(더미 access/secret) + region 으로 빌드해 실제 서명(네트워크 없음): `issue(command, key, ttl)` 결과의 uploadUrl 이 PUT presign(경로에 key, 쿼리에 `X-Amz-Signature`·`X-Amz-Expires`), `requiredHeaders["Content-Type"]==contentType`·`["Content-Length"]==contentLength`, `publicUrl=="{publicBaseUrl}/{key}"`. **FAIL 확인.**

### Implementation for User Story 2

- [ ] T018 [US2] `infra/storage/src/main/kotlin/com/kbap/infra/storage/S3PresignedUploadPort.kt` — `PresignedUploadPort` 구현: `S3Presigner.presignPutObject { PutObjectRequest.builder().bucket(bucket).key(key).contentType(command.contentType).contentLength(command.contentLength) ; signatureDuration(ttl) }` → uploadUrl·서명 헤더 추출 → publicUrl(`publicBaseUrl/key`) 조립 → `PresignedUpload` 반환. (T017 Green.)
- [ ] T019 [US2] `infra/storage/src/main/kotlin/com/kbap/infra/storage/config/StorageConfiguration.kt` — `@Configuration`: `@Bean @ConditionalOnProperty("kbap.storage.bucket")` 로 `S3Presigner`(region, `DefaultCredentialsProvider`) + `S3PresignedUploadPort`(bucket·publicBaseUrl 주입) 조립. 설정 있으면 이 실 port 가 `@ConditionalOnMissingBean` fallback(T016)보다 우선.
- [ ] T020 [P] [US2] 프로필 yml 설정 — `app/api/src/main/resources/application.yml`(기본: `kbap.upload.*` 값 + `kbap.storage.region`·빈 `bucket`/`public-base-url`), `application-dev/staging/prod.yml`(실 `bucket`·`region`·`public-base-url`=CloudFront/공개 베이스), `application-local.yml`(빈 bucket → fallback). 자격증명은 yml 하드코딩 금지(DefaultCredentialsProvider).

**Checkpoint**: `./gradlew :infra:storage:test` Green. `kbap.storage.bucket` 설정 프로필에서 실 서명 URL 발급.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T021 [P] `./gradlew :app:api:test --tests "*ErrorCodeStatusTest*"` — UPLOAD-001/002/003 형식·유일성 통과 확인(실패 시 코드 정정).
- [ ] T022 [P] `./gradlew :app:api:test --tests "*ModuleBoundaryTest*"` — ArchUnit 경계 확인(:application 이 :infra:storage 미참조, 컨트롤러 매핑 `/api/v` 시작, :infra:storage → 상위 계층 미의존).
- [ ] T023 부팅 안전 검증 — 빈 `kbap.storage.bucket`(local/test)로 `:app:api` 컨텍스트 로드 성공(fallback port). 필요 시 스모크 테스트로 고정.
- [ ] T024 [P] `specs/kb-145-presigned-url/quickstart.md` 절차대로 설정·엔드포인트·클라이언트 업로드 흐름 재확인(문서 최신화).

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup(P1)**: 즉시 시작. T001·T003 [P], T002·T004 순차(파일 단독).
- **Foundational(P2)**: Setup 후. 내부 순서 T006→T007→T008(타입 참조 체인), T005·T009 [P]. **모든 스토리 차단**.
- **US1(P3)**: Foundational 후. US2 와 독립(페이크 port).
- **US2(P4)**: Foundational 후. US1 과 독립(실 port 단위 검증). 통합은 T019 조립 시.
- **Polish(P5)**: US1·US2 후.

### Within Each Story
- 테스트 먼저 작성·FAIL 확인(헌법 I) → 구현 Green.
- US1: T010/T011(Red) → T012·T013·T014(구현, 일부 [P]) → T015 → T016.
- US2: T017(Red) → T018 → T019 → T020.

### Parallel Opportunities
- Setup: T001 ∥ T003.
- Foundational: T005 ∥ T009 (그리고 T006 선행 후 T007→T008).
- US1 테스트: T010 ∥ T011. US1 구현: T013 ∥ T014(서로 다른 모듈/파일).
- US1 과 US2 는 Foundational 이후 **서로 병렬 진행 가능**(다른 개발자/다른 모듈).

---

## Parallel Example: User Story 1

```bash
# US1 테스트 먼저(반드시 FAIL):
Task: "ImageUploadApplicationServiceTest — 페이크 port 로 검증 (application/.../upload/)"
Task: "ImageUploadControllerTest + FakePresignedUploadPortConfig (app/api/.../upload/)"

# US1 구현 병렬:
Task: "UnavailablePresignedUploadPort (application/.../upload/)"
Task: "UploadUrlRequest·UploadUrlResponse DTO (app/api/.../upload/)"
```

---

## Implementation Strategy

### MVP (US1 + US2 = 완전한 발급 기능)
1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 US1 → 페이크 port 로 엔드포인트·정책 독립 검증(**STOP & VALIDATE**).
3. Phase 4 US2 → 실 S3 서명 어댑터 독립 검증 후 조립.
4. Phase 5 Polish → 경계·에러코드·부팅 안전·문서.

두 P1 스토리가 함께 완성돼야 실사용 가능(US1=계약, US2=실 서명). US1 만으로도 앱 계층 계약은 데모 가능(페이크 port).

### 범위 밖(별도 태스크)
버킷/IAM/CloudFront 프로비저닝, 업로드 완료 추적·썸네일·삭제 수명주기, 소비 기능(프로필/리뷰/메뉴판 스캔) 연결.

---

## Notes
- [P] = 다른 파일·의존 없음. [US1]/[US2] = 스토리 추적.
- 구현 전 테스트 FAIL 확인(헌법 I). 작업/논리 단위마다 커밋.
- 신규 의존성 1(AWS SDK v2), 마이그레이션 0, DB·엔티티 0. `:app:batch`·도메인 모듈 범위 밖.
- port 발급은 로컬 SigV4 서명 — 발급 시 S3 미호출(테스트가 네트워크 불필요한 근거).
