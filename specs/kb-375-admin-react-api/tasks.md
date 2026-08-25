# Tasks: 신 관리자(React)용 관리자 REST API (KB-375)

**Input**: Design documents from `specs/kb-375-admin-react-api/` — plan.md · spec.md · research.md(결정 D1~D22) · data-model.md · contracts/{admin-auth-audit,admin-foods,admin-members}.md · quickstart.md(시나리오 Q1~Q24)

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution I). 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어), 통합 테스트는 `@SpringBootTest @AutoConfigureMockMvc @Import(MySqlContainerConfig::class)` + `SpringExtension`. 구현 전 테스트 작성 → **실패 확인(Red)** → 최소 구현(Green) → 리팩터.

**Organization**: 스토리별 단계. US1(자격·감사) → US2(검수·전이) → US3(탐색) → US7(구 화면 병행 — US2 의 검증기·전이에 의존해 US2 직후) → US4(파이프라인 개입) → US5(회원 탐색) → US6(회원 조치) → Polish. plan "구현 순서 제안" 과 동일.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 태스크 의존 없음)
- **[Story]**: US1~US7
- 경로는 리포 루트 기준. 규약: Kotlin 소스 주석 금지, 컨트롤러는 Spring 애너테이션·`*Api` 인터페이스는 swagger 만, 응답 `ResponseEntity<BaseResponse<T>>`, 경로 `ApiPaths.ADMIN` + `version = "1.0+"`, 서비스 public 메서드 명시 `@Transactional`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 스키마·에러 코드·설정·테스트 공용 헬퍼 — 모든 스토리가 쓴다.

- [ ] T001 [P] Flyway 마이그레이션 `api/src/main/resources/db/migration/V2026.08.25.<HHmmss>__admin_audit_log_table.sql` — data-model §admin_audit_log 대로 테이블 + 인덱스 3(`idx_admin_audit_target`, `idx_admin_audit_admin`, `idx_admin_audit_action`). 파일명 시각은 생성 시점 로컬 시각
- [ ] T002 [P] Flyway 마이그레이션 `…__member_suspension_columns.sql` — `member.suspended_at DATETIME(6) NULL`, `suspend_reason VARCHAR(500) NULL`
- [ ] T003 [P] Flyway 마이그레이션 `…__food_content_outbox_cancel_and_error.sql` — `outbox_status` ENUM 에 `CANCELED` 추가, `last_error VARCHAR(500) NULL`, `last_failed_at DATETIME(6) NULL`, 인덱스 `idx_food_content_outbox_status_sent(outbox_status, sent_at)`
- [ ] T004 [P] `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 research D19 의 9개 추가: `ADMIN_LOGIN_FAILED(AUTH-009,401)`·`ADMIN_LOGIN_LOCKED(AUTH-010,403)`·`MEMBER_SUSPENDED(MEMBER-012,403)`·`FOOD_INVALID_TRANSITION(FOOD-005,409)`·`FOOD_INVALID_CONTENT(FOOD-006,400)`·`DUPLICATE_FOOD_NAME(FOOD-007,409)`·`IMAGE_COLLECT_IN_PROGRESS(FOOD-008,409)`·`IMAGE_REGENERATION_IN_PROGRESS(FOOD-009,409)`·`FOOD_CONTENT_REQUEST_NOT_PENDING(FOOD-010,409)`. `ErrorCodeStatusTest` 통과 확인
- [ ] T005 [P] `api/src/main/resources/application.yml` 과 `api/src/test/resources/application.yml` 에 `kbap.auth.admin.access-ttl: 1h`·`refresh-ttl: 7d`, `kbap.admin.login-lock.max-attempts: 5`·`lock-duration: 15m` 추가
- [ ] T006 [P] 테스트 공용 헬퍼 `api/src/test/kotlin/com/kbap/api/admin/AdminTestTokens.kt` — `adminAccessToken(tokenIssuer, adminId=1)`, `userAccessToken(tokenIssuer, memberId)`, `adminCookie(...)`, `seedAdminAccount(dataSource, loginId, rawPassword)`(BCrypt 삽입), `adminHeaders(token)`(Authorization + `X-API-Version: 1.0`) 제공
- [ ] T007 [P] `api/src/main/kotlin/com/kbap/api/core/config/OpenApiConfig.kt` 에 `GroupedOpenApi` `admin` 그룹(`pathsToMatch("/api/admin/**")`) 추가 (D20)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 관리자 주체 식별·감사 기록기·검증기·전이 규칙 — 모든 쓰기 스토리의 전제.

**⚠️ CRITICAL**: US1~US7 착수 전 완료.

### Tests (Red 먼저)

- [ ] T008 [P] 단위 테스트 `api/src/test/kotlin/com/kbap/api/infra/auth/token/JwtRefreshTokenRoleTest.kt` — 갱신 토큰에 `role` 클레임이 실리고 `ParsedRefreshToken.role` 로 파싱되며, role 클레임 없는 구 토큰은 `USER` 로 파싱되는지; ADMIN 발급 시 `kbap.auth.admin.*` TTL 이 쓰이는지
- [ ] T009 [P] 단위 테스트 `api/src/test/kotlin/com/kbap/api/core/auth/AuthAdminIdArgumentResolverTest.kt` — 필터 속성 `authAdminId` 를 돌려주고, 속성 없음/USER 롤이면 `ADMIN_FORBIDDEN`
- [ ] T010 [P] 통합 테스트 `api/src/test/kotlin/com/kbap/api/core/auth/JwtAuthenticationFilterAdminAttributeTest.kt` — ADMIN 토큰은 request 에 `authAdminId` 만, USER 토큰은 `authMemberId` 만 심는지(테스트용 `@RestController` 로 속성 echo)
- [ ] T011 [P] 단위 테스트 `api/src/test/kotlin/com/kbap/api/admin/FoodContentValidatorTest.kt` — 설명 0/256자, 맵기 -1/11, 번역 `ja` 누락·빈 문자열, 재료 코드 `PEANUTS`(카탈로그 밖), 비율 0/101, 재료 누락(null) vs 빈 배열, 이름 정규화 후 빈 문자열 → 각각 `FieldError(field, code)` 가 정확히 나오는지
- [ ] T012 [P] 단위 테스트 `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodTransitionTest.kt` — data-model 전이 표 전 케이스: `allowedTransitions()` 집합, APPROVE 전제(재료 null / 이미지 없음 → `IllegalStateException` 류 도메인 예외), REJECT 사유 필수·attempts 증가, RESUBMIT 이미지 유무 분기·실패 필드 clear, UNPUBLISH, 비허용 전이 거부, `replaceImage`(PENDING_IMAGE→PENDING_REVIEW / READY 유지)
- [ ] T013 [P] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminAuditRecorderTest.kt` — `record(...)` 가 같은 트랜잭션에서 `admin_audit_log` 행(변경 필드만 JSON) 을 남기고, 호출 트랜잭션 롤백 시 함께 롤백되는지

### Implementation

- [ ] T014 `common/src/main/kotlin/com/kbap/common/port/auth/ParsedRefreshToken.kt` 에 `role: MemberRole = MemberRole.USER` 추가; `api/src/main/kotlin/com/kbap/api/infra/auth/token/JwtTokenIssuer.kt` 갱신 토큰에 `role` 클레임, ADMIN 이면 admin TTL 사용; `JwtTokenParser.kt` role 파싱(부재 → USER); `JwtTokenProperties.kt`·`api/src/main/kotlin/com/kbap/api/core/config/AuthConfig.kt` 에 `adminAccessTtl`·`adminRefreshTtl` 주입 (D1)
- [ ] T015 `api/src/main/kotlin/com/kbap/api/core/auth/JwtAuthenticationFilter.kt` — role=ADMIN 이면 `AUTH_ADMIN_ID_ATTRIBUTE = "authAdminId"` 속성 + MDC `adminId`, `authMemberId` 는 심지 않음; USER 는 기존 그대로 (D2)
- [ ] T016 [P] `api/src/main/kotlin/com/kbap/api/core/auth/AuthAdminId.kt`(애너테이션) + `AuthAdminIdArgumentResolver.kt` 생성, `WebConfig.addArgumentResolvers` 등록, `OpenApiConfig.addAnnotationsToIgnore` 에 추가
- [ ] T017 [P] `api/src/main/kotlin/com/kbap/api/admin/AdminPageAuthInterceptor.kt` — 쿠키 토큰 파싱 성공 시 request 에 `authAdminId` 속성 세팅(구 화면 조작의 감사 조작자 공급)
- [ ] T018 [P] 감사 로그 엔티티/enum/리포지토리 — `common/src/main/kotlin/com/kbap/common/domain/admin/model/AdminAuditLog.kt`(BaseEntity, 컬럼 data-model 대로, JSON 컬럼은 `Map<String, Any?>?` + 기존 JSON 컨버터 패턴), `AdminAuditAction.kt`·`AdminAuditTargetType.kt` enum, `common/.../domain/admin/AdminAuditLogJpaRepository.kt`(`findByTargetTypeAndTargetIdOrderByIdDesc(…, pageable)`) + `AdminAuditLogQueryRepository`/`Impl`(네이티브 동적 조건: targetType·targetId·adminAccountId·action·from·to, 페이지)
- [ ] T019 `api/src/main/kotlin/com/kbap/api/admin/AdminAuditRecorder.kt` — `@Component`, `fun record(adminId: Long, action, targetType, targetId: Long?, before: Map<String, Any?>?, after: Map<String, Any?>?, note: String? = null)` (`@Transactional(propagation = MANDATORY)` — 호출자 트랜잭션 필수), `diff(beforeMap, afterMap)` 로 변경 필드만 남기는 헬퍼 (D4). T013 Green
- [ ] T020 [P] `api/src/main/kotlin/com/kbap/api/admin/FieldError.kt` + `FoodContentValidator.kt` — `@Component`, `IngredientCode` 집합·`LanguageCode` 대상 9개 기반 검증, `validate(FoodContentCandidate): List<FieldError>`; `AdminFoodContentIngestRequest.kt` 의 `@AssertTrue` 7개를 검증기 위임으로 치환(메시지 동일). `AdminFoodContentIngestValidationTest` 회귀 통과 (D5). T011 Green
- [ ] T021 `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodTransition.kt` enum + `Food.kt` 에 `allowedTransitions(): Set<FoodTransition>`, `transition(t: FoodTransition, reason: String?)`, `replaceImage(ref)`, `approve()` 전제 강화(재료 non-null ∧ imageRef non-null, 위반 시 `IllegalStateException`), RESUBMIT/UNPUBLISH 구현, FAILED 이탈 시 실패 필드 clear; `FoodContentStatus.kt` 에 `displayName` (D6·D13). T012 Green
- [ ] T022 [P] `common/src/main/kotlin/com/kbap/common/port/auth/LoginAttemptStore.kt` seam(`recordFailure(key): Int`, `reset(key)`, `isLocked(key): Boolean`) + `api/src/main/kotlin/com/kbap/api/infra/redis/RedisLoginAttemptStore.kt`(키 `admin:login-fail:{key}`, INCR + EXPIRE lock-duration, max 도달 시 잠금) + `PropertiesConfig`/`AuthConfig` 조립 (D3)
- [ ] T023 `api/src/main/kotlin/com/kbap/api/core/GlobalExceptionHandler.kt` — `IllegalStateException`(도메인 전이 위반) 을 409 `FOOD_INVALID_TRANSITION` 으로 매핑하지 **않고**, 관리자 서비스가 `BusinessException(FOOD_INVALID_TRANSITION, payload = mapOf("allowed" to …, "reason" to …))` 로 변환하도록 `AdminFoodCommandService` 설계 메모만 남김(핸들러 변경 없음). 대신 `BusinessException` payload 가 `errors[]`·`allowed[]` 를 실어 나가는지 `AdminControllerTest` 류 기존 테스트로 확인 — 변경 필요 없으면 태스크 완료 표기

**Checkpoint**: 관리자 주체 분리·감사 기록기·검증기·전이 규칙 준비. 기존 전 테스트 그린 확인(`./gradlew :api:test :common:test`).

---

## Phase 3: User Story 1 — 신 관리자 화면 로그인과 감사 기록 (Priority: P1) 🎯 MVP

**Goal**: JSON 로그인/갱신/로그아웃, 5회 잠금, 관리자 자격과 회원 자격 교차 거부, 감사 이력 조회.

**Independent Test**: quickstart Q1~Q4.

### Tests for User Story 1 ⚠️

- [ ] T024 [P] [US1] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminAuthControllerTest.kt` — 로그인 성공(`accessToken`·`refreshToken`·`expiresIn`), 불일치 401 AUTH-009, 5회 실패 후 정답 403 AUTH-010(Redis 카운터는 테스트 프로필 Redis 컨테이너 또는 인메모리 대체 — 기존 `RedisRefreshTokenStore` 테스트 방식 재사용), 갱신 회전(재사용 401 AUTH-005), USER 갱신 토큰으로 관리자 refresh 401, 관리자 갱신 토큰으로 `POST /api/auth/refresh` 401 AUTH-005, 로그아웃 후 갱신 401, 관리자 토큰으로 `GET /api/members/me` 401 AUTH-003, 로그인 성공 시 감사 `ADMIN_LOGIN` 행
- [ ] T025 [P] [US1] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminAuditLogControllerTest.kt` — 시드 3행(대상·조작자·action 상이) 후 `GET /api/admin/audit-logs` 필터별(targetType+targetId / adminAccountId / action / from~to) 결과와 페이지 메타, USER 토큰 403, `before`/`after` 가 JSON 객체로 직렬화되는지

### Implementation for User Story 1

- [ ] T026 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminAuthService.kt` — 기존 `AdminLoginService` 를 흡수/확장: `login(id, pw): AdminTokens`(잠금 확인 → BCrypt → 실패 카운트/성공 리셋 → access+refresh 발급·`RefreshTokenStore.save` → 감사 `ADMIN_LOGIN`), `refresh(refreshToken)`(role≠ADMIN → AUTH-005, consume·회전), `logout(refreshToken?)`. 구 화면 `AdminPageController` 는 `login` 의 access 토큰만 사용하도록 연결 유지
- [ ] T027 [US1] `api/src/main/kotlin/com/kbap/api/auth/AuthService.kt` `refresh` 에 `parsed.role != MemberRole.USER → INVALID_REFRESH_TOKEN` 추가; 기존 `AuthServiceTest`/컨트롤러 테스트 그린 유지
- [ ] T028 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminAuthApi.kt`(swagger — `@Tag("관리자 인증")`, 로그인/갱신/로그아웃 3개, 401/403 응답 문서) + `AdminAuthController.kt`(`@RequestMapping(ApiPaths.ADMIN + "/auth", version = "1.0+")`, `AdminLoginRequest(id, password)`·`AdminTokenResponse(accessToken, refreshToken, expiresIn)`·`AdminRefreshRequest`·`AdminLogoutRequest` DTO 같은 패키지). 로그인·갱신은 JWT 필터 예외 필요 — `WebConfig` 의 `jwtAuthenticationFilterRegistration` 에 `GuestExemption("POST", "^/api/admin/auth/(login|refresh)$")` 추가하고 `AdminAuthorizationInterceptor` 도 같은 경로 제외(`excludePathPatterns`). T024 Green
- [ ] T029 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminAuditQueryService.kt`(`@Transactional(readOnly = true)`, `getAuditLogPage(filter, page, size): AdminAuditLogPage` — `admin_account.login_id` 를 id 묶음 조회로 join 없이 매핑) + `AdminAuditLogApi.kt` + `AdminAuditLogController.kt`(`GET ApiPaths.ADMIN + "/audit-logs"`, size ≤200). T025 Green

**Checkpoint**: 관리자 SPA 가 로그인해 관리자 API 를 호출하고 감사 이력을 볼 수 있다.

---

## Phase 4: User Story 2 — 음식 콘텐츠 검수와 안전한 상태 전이 (Priority: P1)

**Goal**: 승인/반려/전이 REST, `version`+검증기 기반 수정 REST, 상태 불수용.

**Independent Test**: quickstart Q7~Q9.

### Tests for User Story 2 ⚠️

- [ ] T030 [P] [US2] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminFoodCommandControllerTest.kt` — `PUT /api/admin/foods/{id}`: 정상(200·상세 응답·감사 `FOOD_UPDATE` before/after 변경 필드만·READY 면 벡터 UPSERT PENDING 1), `version` 불일치 409 COMMON-004 + `currentVersion`, `contentStatus` 포함 시 400, 카탈로그 밖 재료 400 FOOD-006 `errors[0].field == "ingredients[0].code"`, 비율 0 400, `ja` 누락 400, 이름 중복 409 FOOD-007; `approve`: PENDING_REVIEW+이미지+재료 → READY+벡터, 이미지 없음 409 FOOD-005 `reason NO_IMAGE`, READY 재승인 200 멱등; `reject`: 사유 없음 400, 정상 → FAILED attempts+1 사유 기록; `transitions`: RESUBMIT 이미지 유무 분기·실패 필드 clear, UNPUBLISH → PENDING_REVIEW + 벡터 DELETE, 비허용 409 FOOD-005 `allowed[]`; USER 토큰 403

### Implementation for User Story 2

- [ ] T031 [US2] 요청/응답 DTO `api/src/main/kotlin/com/kbap/api/admin/AdminFoodUpdateRequest.kt`(version 필수, `ingredients: List<IngredientInput(code, inclusionPercent)>`, `contentStatus` 는 필드 없음 — `@JsonIgnoreProperties(ignoreUnknown = false)` 로 미지 필드 400), `AdminFoodTransitionRequest.kt`(transition, reason?), `AdminFoodRejectRequest.kt`, `AdminFoodTransitionResponse.kt`(id, contentStatus{code,label}, allowedTransitions, version)
- [ ] T032 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodCommandService.kt` — `@Transactional` `updateFood(adminId, id, req)`(버전 비교 → 검증기 → 이름 정규화·중복(`findByKoreanNameIn`) → 필드 적용 → 벡터 UPSERT(READY) → 감사), `approve/reject/transition(adminId, id, …)`(`Food.transition` 호출, `IllegalStateException`/비허용 → `BusinessException(FOOD_INVALID_TRANSITION, payload{allowed, reason})`, 벡터 예약, 감사 `FOOD_APPROVE|FOOD_REJECT|FOOD_TRANSITION`). 기존 `AdminFoodContentReviewService.applyContentReviewResult` 는 이 서비스의 approve/reject 를 재사용하도록 위임(랭체인 콜백 계약 무변경, 조작자는 랭체인 = 감사 `adminAccountId` 를 시스템 계정 0 으로 기록)
- [ ] T033 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodCommandApi.kt`(swagger) + `AdminFoodCommandController.kt` — `PUT /api/admin/foods/{id}`, `POST …/{id}/approve`, `POST …/{id}/reject`, `POST …/{id}/transitions`, `@AuthAdminId adminId`. 상세 응답은 US3 의 `AdminFoodDetailResponse` 를 쓰므로 US3 T037 이 없으면 임시로 `AdminFoodTransitionResponse` 반환 후 T037 완료 시 교체. T030 Green

**Checkpoint**: 상태 드롭다운 없이 승인·반려·전이·안전한 수정이 REST 로 가능.

---

## Phase 5: User Story 7 — 구 관리자 화면 병행 유지 (Priority: P1, 횡단)

**Goal**: 구 화면이 그대로 동작하되 US2 의 검증기·버전·전이 규칙·감사 기록을 공유하고, 상태 드롭다운 백도어를 닫는다.

**Independent Test**: quickstart Q24 + 기존 20개 관리자 테스트 그린.

### Tests for User Story 7 ⚠️

- [ ] T034 [P] [US7] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`·`AdminFoodListControllerTest.kt` 보강 — 편집 폼 POST 에 `version` 포함 시 200 redirect `updated`, 이전 version → `error=stale`, 맵기 8 저장 성공, `contentStatus` 파라미터를 보내도 상태 불변, 카탈로그 밖 재료 → `error=invalid-ingredient`, 상세 승인 폼 `POST /admin/foods/{id}/approve` → READY, 반려 폼(사유) → FAILED, 구 화면 수정 후 `admin_audit_log` 에 `FOOD_UPDATE` 행(조작자 = 쿠키 토큰 subject). 기존 상태 드롭다운 변경 시나리오는 삭제/이관

### Implementation for User Story 7

- [ ] T035 [US7] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` `updateFood(id, command, expectedVersion: Long, adminId: Long)` — 버전 비교(`STALE` 결과 enum 추가), `FoodContentValidator` 사용(`INVALID_INGREDIENT`/`INVALID_CONTENT` 결과 enum 추가), `contentStatus` 제거, 감사 기록; `UpdateFoodCommand` 에서 `contentStatus` 제거. `AdminFoodServiceTest` 갱신
- [ ] T036 [US7] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` — `updateFood` 에 `@RequestParam version: Long` 추가·`contentStatus` 파라미터 제거·`error=stale|invalid-ingredient|invalid-content` 리다이렉트, `POST /admin/foods/{id}/approve`·`/reject`(reason) 핸들러 추가(`AdminFoodCommandService` 재사용, `request.getAttribute("authAdminId")` 로 조작자); `api/src/main/resources/templates/admin/food-list.html` — `<input type="hidden" name="version">`, 맵기 `max="10"`, 상태 select → 읽기 전용 배지, 상세 패널에 승인/반려 폼(PENDING_REVIEW 일 때만), 새 에러 배너 3종 문구. T034 Green + 기존 페이지 테스트 7개 그린

**Checkpoint**: 구 화면 운영 공백 없음, 백도어 제거, 규칙 단일화.

---

## Phase 6: User Story 3 — 음식 탐색과 상황 파악 (Priority: P1)

**Goal**: 목록(검색·필터·정렬·페이지·삭제 포함)·상세(이력 동봉)·대시보드(기간·라벨·비용)·재료 카탈로그.

**Independent Test**: quickstart Q5·Q6·Q18.

### Tests for User Story 3 ⚠️

- [ ] T037 [P] [US3] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminFoodQueryControllerTest.kt` — 시드 음식 6건(상태·실패유형·재료·번역 상이, 1건 소프트 삭제) 후 `GET /api/admin/foods`: `q=<id>` 1건, `q=김치` contains, `ingredient=CHICKEN`, `translation=Ginseng`, `status`, `failureKind=NOT_FOOD`, `includeDeleted` 유무, `sort=updatedAt,asc`, `size=2` 페이지 메타, `size=201` 400; 목록 항목 필드(`hasImage`·`vectorSyncStatus`·`reviewCount`·`deleted`); `GET /api/admin/foods/{id}`: 이력 배열 4종·`reviewSummary`·`scanMatchCount`·`bookmarkCount`·`allowedTransitions`·`ingredients[].inclusionPercent`, 삭제 음식 `deleted:true`, 없는 id 400 FOOD-001; `GET /api/admin/ingredients` 81건
- [ ] T038 [P] [US3] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminDashboardControllerTest.kt` — `days` 기본 7·`days=30` 배열 길이·`days=0|91` 400, `foods.byStatus[].label` 이 `displayName`, `contentOutbox.stuckCount`(SENT+sent_at 4시간 전 시드), `llmCost.scopeNote`·`costKrw`, `heightPct`/`dayLabel` 부재. 기존 `AdminDashboardMetricsServiceTest` 갱신

### Implementation for User Story 3

- [ ] T039 [P] [US3] 리포지토리 쿼리 — `common/.../domain/food/dto/AdminFoodRow.kt` 프로젝션 + `FoodAdminQueryRepository.kt`/`FoodAdminQueryRepositoryImpl.kt`(`EntityManager` 네이티브 동적 SQL: id/표시명/재료 `JSON_SEARCH`/번역 `JSON_SEARCH`/status/failureKind/includeDeleted/sort 화이트리스트/페이지 + count), `FoodJpaRepository.kt` 에 `findByIdIncludingDeleted(id)`(native) (D7)
- [ ] T040 [P] [US3] 이력·집계 쿼리 — `FoodContentOutboxJpaRepository.findTop10ByFoodIdOrderByIdDesc`, `ImageBatchItemJpaRepository.findTop10ByFoodIdOrderByIdDesc` + `ImageBatchJpaRepository.findByIdIn`, `FoodVectorOutboxJpaRepository.findTop5ByFoodIdOrderByIdDesc` + `findLatestStatusByFoodIds(ids)`(native, 목록 `vectorSyncStatus` 용), `ScanHistoryJpaRepository.countByFoodId`, `BookmarkJpaRepository.countByFoodId`, `ReviewJpaRepository.countByFoodIdIn`(목록 reviewCount — 기존 `aggregateRatingsByFoodIds` 재사용 가능하면 생략), `IngredientJpaRepository.findAllByOrderByCode` (D8)
- [ ] T041 [P] [US3] 대시보드 확장 — `LlmCallCostJpaRepository.sumDailyByModelSince` 와 `dto/DailyModelCostSum.kt` 에 `costKrw` 합계 추가; `FoodContentOutboxJpaRepository.countStuck(status, before: LocalDateTime)` (D13·D11)
- [ ] T042 [US3] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodQueryService.kt`(`@Transactional(readOnly = true)`: `getFoodPage(filter, page, size)`, `getFoodDetail(id)` — 본체 + 이력 4종 + 집계 + 감사 10건, `getIngredients()`) + 응답 DTO `AdminFoodListResponse.kt`·`AdminFoodDetailResponse.kt`(contracts/admin-foods.md 형태, 상태 `{code,label}`)·`AdminIngredientResponse.kt` + `AdminFoodQueryApi.kt` + `AdminFoodQueryController.kt`(`GET /api/admin/foods`, `GET …/{id}`, `GET /api/admin/ingredients`). T037 Green. T033 의 상세 응답 교체
- [ ] T043 [US3] `AdminFoodDashboardService`·`AdminDashboardMetricsService`·`AdminFoodOutboxQueryService` 확장(`days` 파라미터, `heightPct/dayLabel` 제거는 REST 응답 DTO 에서만 — 구 화면 뷰 모델은 유지, `stuckCount`, `costKrw`, `scopeNote` 상수) + `AdminDashboardResponse.kt` + `AdminDashboardApi.kt` + `AdminDashboardController.kt`(`GET /api/admin/dashboard?days`). T038 Green

**Checkpoint**: React 가 목록·상세·대시보드를 한 응답씩으로 그릴 수 있다.

---

## Phase 7: User Story 4 — 파이프라인 개입 (Priority: P2)

**Goal**: 개별 재수집, READY 유지 이미지 재생성/업로드, 배치 아이템·즉시 회수·재제출, 콘텐츠 아웃박스 고착/requeue/cancel, 벡터 일괄 재시도/enqueue 결과, 시드 상세, 삭제/복구, 일괄 작업.

**Independent Test**: quickstart Q10~Q17.

### Tests for User Story 4 ⚠️

- [ ] T044 [P] [US4] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPipelineControllerTest.kt` — 개별 재수집 2회(`created` true→false, PENDING 1건), 조건 일괄 재수집 필터(`ingredient`·`failureKind` 추가); 콘텐츠 아웃박스 목록 `stuckHours=0` 에서 SENT 건 `stuck:true`·`lastError`, requeue(SENT→PENDING sentAt null), cancel(→CANCELED), PENDING 아닌 건 requeue 409 FOOD-010, CANCELED 건에 랭체인 적재 `POST /api/admin/foods/contents` → 400; 벡터 목록 페이지, `retry-all-failed` 카운트·attempts 0, `enqueue` `{enqueued, remaining}`, 단건 retry 부적합 409
- [ ] T045 [P] [US4] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminFoodImageReplaceTest.kt` — `regenerate`: READY 음식 → 배치 1·아이템 1·상태 READY 유지·감사, 진행 중 재요청 409 FOOD-009(`FoodImageBatchClient` 는 기존 테스트의 페이크 빈 재사용); `upload-url` 형식/크기 오류 UPLOAD-001/003, `PUT …/image` 존재하지 않는 키 400 IMAGE-003(`StorageObjectStore` 페이크 `head`), 성공 시 `imageRef` 교체·READY 유지·벡터 UPSERT, PENDING_IMAGE 음식은 PENDING_REVIEW 로; 회수 `handleResult` 가 READY 음식에 `replaceImage` 로 동작(기존 `FoodImageBatchCollectServiceTest` 보강)
- [ ] T046 [P] [US4] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminImageBatchControllerTest.kt` — 배치 목록 페이지(`openaiBatchId`·`promptVersion` 노출), `candidates/count`, 배치 상세 아이템(`errorMsg`·`displayName`), `collect`(SUBMITTED 배치 + 페이크 클라이언트 결과 → 카운트 응답), 락 점유 중 409 FOOD-008(`LockProvider` 로 락 선점 후 호출), `items/resubmit` → 새 배치
- [ ] T047 [P] [US4] `AdminFoodCommandControllerTest.kt` 에 추가 — `DELETE /api/admin/foods/{id}` 소프트 삭제·벡터 DELETE·재호출 200, `restore` 복귀·벡터 UPSERT, 동명 활성 존재 409 FOOD-007, `bulk`(APPROVE 3건 중 1건 이미지 없음 → 건별 결과, DELETE, RECOLLECT, 501건 400); `AdminControllerTest.kt` 시드 응답 `createdIds/skippedNames/blockedByDeletedNames`(삭제 음식 이름 충돌 시드) + 기존 3필드 유지

### Implementation for User Story 4

- [ ] T048 [P] [US4] 도메인/리포 — `FoodContentOutbox.kt` `requeue()`·`cancel()`·`lastError/lastFailedAt` 필드, `FoodContentOutboxStatus.CANCELED`; `FoodContentOutboxJpaRepository` `recordPublishFailed(ids, error)` 시그니처 확장 + `findPage(status?, foodId?, pageable)` + `countStuck`; `batch/src/main/kotlin/com/kbap/batch/outbox/FoodContentOutboxPublisher.kt` 실패 시 `e.message?.take(500)` 전달(배치 테스트 갱신); `FoodVectorOutboxJpaRepository.findByOutboxStatus(status, pageable)`·`retryAllFailed(): Int`(native); `FoodJpaRepository.restore(id)`(native UPDATE status)·`findKoreanNamesIncludingDeleted(names)`(native) (D11·D12·D14)
- [ ] T049 [P] [US4] 이미지 — `common/.../domain/image/model/UploadPurpose.kt` 에 `FOOD("food")`; `api/src/main/kotlin/com/kbap/api/food/FoodImageBatchSubmitService.kt` 에 `submitForFoods(foodIds: List<Long>): FoodImageSubmitResult`(진행 중 PENDING 아이템 존재 → FOOD-009) + `countCandidates()`; `FoodImageBatchCollectService.kt` `handleResult` 를 `food.replaceImage(key)` 로 전환 + `collectSubmitted(): ImageCollectSummary(collectedBatches, doneItems, failedItems)` 반환; `ImageBatchItemJpaRepository.existsByFoodIdAndItemStatus`·`findByIdIn`; `ImageBatchJpaRepository.findAll(pageable)` 활용 (D9·D10)
- [ ] T050 [US4] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPipelineService.kt` — `recollectOne(adminId, id)`, `recollect(adminId, filter)`(기존 `requestRecollect` 확장: ingredient/failureKind 필터), `regenerateImage(adminId, id)`, `issueImageUploadUrl(adminId, id, contentType, length)`(`PresignedUploadService` 재사용 — 키 `{prefix}/images/food/{yyyy}/{MM}/admin{adminId}_{uuid}.{ext}`), `replaceImage(adminId, id, objectKey)`(`StorageObjectStore.head` 검증 → `replaceImage` → 벡터 UPSERT(READY)), `getImageBatchPage`, `countImageCandidates`, `getImageBatchDetail(batchId)`, `collectImagesNow()`(`LockingTaskExecutor.executeWithLock("food-image-collect")` — 락 실패 → FOOD-008), `resubmitItems(adminId, itemIds)`, `getContentOutboxPage(filter)`, `requeueContentOutbox/cancelContentOutbox(adminId, id)`, `getVectorOutboxPage`, `enqueueVectors(adminId)`→`{enqueued, remaining}`, `retryVector(adminId, id)`, `retryAllFailedVectors(adminId)`. 전부 감사 기록(`CONTENT_OUTBOX_REQUEUE|CANCEL`, `VECTOR_RETRY|ENQUEUE`, `IMAGE_COLLECT|RESUBMIT`, `FOOD_RECOLLECT`, `FOOD_IMAGE_REGENERATE|REPLACE`)
- [ ] T051 [US4] `AdminFoodPipelineApi.kt` + `AdminFoodPipelineController.kt` — contracts/admin-foods.md 의 재수집·이미지·이미지 배치·콘텐츠 아웃박스·벡터 아웃박스 엔드포인트 전부(경로·본문·응답 DTO 같은 패키지 `AdminPipelineResponses.kt`). 구 화면 `AdminFoodPageController` 의 벡터 enqueue/retry 는 새 서비스로 위임(동작 동일). T044·T045·T046 Green
- [ ] T052 [US4] `AdminFoodCommandService` 에 `deleteFood/restoreFood/bulk(adminId, action, ids)`(건별 `TransactionTemplate(REQUIRES_NEW)`, 결과 목록) 추가 + `AdminFoodService.seedIncomplete` 반환 확장(`createdIds`·`skippedNames`·`blockedByDeletedNames`, `SeedIncompleteResult.kt` 필드 추가)과 `AdminFoodSeedResponse.kt` 확장 + `AdminFoodCommandController` 에 `DELETE …/{id}`·`POST …/{id}/restore`·`POST /api/admin/foods/bulk` 추가(`AdminFoodBulkRequest/Response.kt`). 구 화면 삭제 핸들러도 새 서비스로 위임. T047 Green

**Checkpoint**: 운영자가 파이프라인에 개입하는 전 조작이 REST 로 가능.

---

## Phase 8: User Story 5 — 회원 탐색 (Priority: P2)

**Goal**: 회원 검색/필터/정렬/탈퇴자 포함 목록, 마스킹·활동 이력·랭킹 포함 상세, 랭킹 원장.

**Independent Test**: quickstart Q19·Q20.

### Tests for User Story 5 ⚠️

- [ ] T053 [P] [US5] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminMemberControllerTest.kt`(조회 파트) — 회원 5명(provider·상태·온보딩·가입일 상이, 1명 탈퇴) 시드 후 `GET /api/admin/members` 필터별·`includeWithdrawn`·정렬·`size` 메타·`email` 마스킹(`ab***@gmail.com`)·`withdrawn/withdrawnAt`; `GET …/{id}`: `providerUid` 키 부재, `scan.scanAllowed`, `ranking.pointsToNext`, `activity.*Count`(리뷰·주문·스캔·북마크·신고 filed/received·차단 시드), `recentScans/Reviews/Orders` 각 ≤5, 탈퇴 회원 조회 가능, 없는 id 400 MEMBER-003; `GET …/{id}/ranking-events` 페이지

### Implementation for User Story 5

- [ ] T054 [P] [US5] 리포지토리 — `common/.../domain/member/dto/AdminMemberRow.kt` + `MemberAdminQueryRepository.kt`/`Impl.kt`(네이티브 동적: q(id/닉네임)/email/provider/memberStatus/onboarding/createdFrom~To/includeWithdrawn/sort/page) + `findByIdIncludingWithdrawn(id)`; `MemberRankingEventJpaRepository.findByMemberIdOrderByIdDesc(memberId, pageable)`; count 쿼리 — `ReviewJpaRepository.countByMemberId`, `OrderJpaRepository.countByMemberId`, `ScanHistoryJpaRepository.countByMemberId`, `BookmarkJpaRepository.countByMemberId`, `MemberBlockJpaRepository.countByBlockerMemberId`, `ReportJpaRepository.countByReporterMemberId`·`countReceivedByMemberId(memberId)`(native: report ⋈ food_review.member_id) (D7·D16)
- [ ] T055 [US5] `AdminMemberQueryService.kt` 확장 — `getMemberPage(filter, page, size): AdminMemberPageResponse`, `getMemberDetail(id): AdminMemberDetailResponse`(마스킹 `maskEmail`, 활동 집계·최근 5건은 기존 `findMemberReviewPage`/`findPageByMemberId`/`findScannedFoodPageIds` 재사용 + 음식명 id 묶음 조회, `Member.ranking`), `getRankingEventPage`; 구 화면 뷰 모델(`AdminMemberPageView/DetailView`)은 유지하되 `providerUid` 제거·`email` 마스킹 적용(구 화면 개인정보 노출 정리 — `member-detail.html` 해당 줄 수정) + 응답 DTO `AdminMemberResponses.kt` + `AdminMemberApi.kt` + `AdminMemberController.kt`(`GET /api/admin/members`, `GET …/{id}`, `GET …/{id}/ranking-events`). T053 Green

**Checkpoint**: CS 대응에 필요한 회원 탐색 완료.

---

## Phase 9: User Story 6 — 회원 조치 (Priority: P3)

**Goal**: 제재/해제(정지 전용 오류), 프로필 초기화, scan-unlock, 강제 탈퇴, 감사.

**Independent Test**: quickstart Q21~Q23.

### Tests for User Story 6 ⚠️

- [ ] T056 [P] [US6] `AdminMemberControllerTest.kt`(조치 파트) — `PATCH …/status` SUSPENDED 사유 없음 400, 정상(`suspendedAt`·`suspendReason` 상세 반영·감사 `MEMBER_STATUS`), 같은 상태 재요청 200, ACTIVE 복귀 시 두 컬럼 null; `PATCH …/profile` 둘 다 false 400, `resetNickname` → `사용자{id}`, `resetProfileImage` → null; `POST …/scan-unlock` → `scan.scanAllowed true`; `DELETE …/{id}` → `withdrawn true`·재호출 200, `SocialAccountDeleter` 페이크가 예외 → 500 AUTH-007 + 감사 `MEMBER_WITHDRAW_FAILED` note
- [ ] T057 [P] [US6] 통합 테스트 `api/src/test/kotlin/com/kbap/api/admin/AdminMemberSuspensionLoginTest.kt` — 정지 회원 소셜 로그인(`SocialTokenVerifier` 페이크) → 403 MEMBER-012(409 MEMBER-001 아님), 정지 전 발급 토큰으로 `GET /api/members/me` → 403 MEMBER-012, 해제 후 정상

### Implementation for User Story 6

- [ ] T058 [P] [US6] `common/.../domain/member/model/Member.kt` — `suspendedAt`·`suspendReason` 필드, `suspend(reason)`, `reinstate()`, `unlockScan()`, `resetNickname()`, `resetProfileImage()`; `MemberJpaRepository.findByProviderAndProviderUid(provider, uid)`(상태 무관) (D15)
- [ ] T059 [US6] `api/src/main/kotlin/com/kbap/api/member/MemberService.kt` — `findOrSignUp`: 상태 무관 조회 → SUSPENDED 면 `BusinessException(MEMBER_SUSPENDED)`; `getMember`: `findById` 후 SUSPENDED 면 MEMBER_SUSPENDED, 없으면 MEMBER-003(기존 `findByIdAndMemberStatus(ACTIVE)` 대체). 기존 `MemberServiceTest`·`AuthServiceTest` 갱신. T057 Green
- [ ] T060 [US6] `api/src/main/kotlin/com/kbap/api/admin/AdminMemberCommandService.kt` — `changeStatus(adminId, id, status, reason)`, `resetProfile(adminId, id, resetNickname, resetProfileImage)`, `unlockScan(adminId, id)`, `withdraw(adminId, id)`(`AuthService.withdraw` 재사용 — 트랜잭션 밖 외부 호출 선행, 실패 시 감사 `MEMBER_WITHDRAW_FAILED` 를 별도 `REQUIRES_NEW` 로 기록 후 재throw, 이미 탈퇴면 200) + 요청 DTO `AdminMemberStatusRequest.kt`·`AdminMemberProfileResetRequest.kt` + `AdminMemberController` 에 `PATCH …/status`, `PATCH …/profile`, `POST …/scan-unlock`, `DELETE …/{id}` 추가(+ `AdminMemberApi` 문서). T056 Green (D17·D18)

**Checkpoint**: 운영 DB 직접 수정 없이 회원 조치 완료.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [ ] T061 [P] `docs/architecture/meogo-conventions.md`(또는 관리자 절) 에 관리자 자격 분리·감사 기록 규약·검증기 단일 출처 1~2문단 추가; `docs/adr/` 에 ADR "관리자 REST 층과 구 화면 병행·전이 규칙 도메인 소유" 1건
- [ ] T062 [P] `../kbap-agenthub/wiki/` 에 `admin-api-and-audit.md`(관리자 인증 두 갈래·감사 로그·전이 규칙·고착 아웃박스 운영 절차) 작성 + `INDEX.md` 한 줄, `member-auth.md` 의 "SUSPENDED 수동 DB 조작" 문구 갱신
- [ ] T063 quickstart.md Q1~Q24 를 로컬(local 프로필 + Redis)에서 수동 실행하고 결과를 PR 본문 검증 표로 정리
- [ ] T064 `./gradlew build` 전체 그린(ArchUnit `ModuleBoundaryTest` 포함 — `common.domain.admin` 이 타 도메인 타입을 참조하지 않는지, `api.infra.redis` 어댑터 직접 참조가 조립 config 뿐인지) + `kbap-code-review`·`kbap-db-review` 스킬로 리뷰 1회(감사 로그 인덱스·네이티브 쿼리·마이그레이션)
- [ ] T065 `open-draft-pr-to-develop` 스킬로 PR — 본문에 Jira KB-375 링크, 계약 요약, 마이그레이션 3종의 리비전 공존 안전 근거, 구 화면 변경점(버전 hidden·맵기 max·상태 읽기 전용·승인/반려 폼·providerUid 제거)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(1)** → **Foundational(2)** → 스토리들. Foundational 은 자격 분리(T014~T017)·감사(T018~T019)·검증기(T020)·전이(T021)·잠금 seam(T022) 을 포함하며 US1~US7 전부가 의존한다.
- **US1(3)**: Foundational 만. **MVP**.
- **US2(4)**: Foundational(검증기·전이·감사). 상세 응답은 US3 T042 로 교체 예정(임시 응답 허용).
- **US7(5)**: US2 T032(`AdminFoodCommandService`) — 구 화면 승인/반려 폼이 재사용.
- **US3(6)**: Foundational. US2 와 병렬 가능.
- **US4(7)**: US2 T032(bulk 가 approve 재사용)·US3 T040(이력 쿼리 일부) — 그 외 병렬.
- **US5(8)**: Foundational 만. US2~4 와 병렬 가능.
- **US6(9)**: US5 T055(상세 응답으로 결과 확인).
- **Polish(10)**: 전부.

### Within Each Story

테스트 작성 → **실패 확인** → 도메인/리포 → 서비스 → 컨트롤러/Api → 그린 → 감사 기록·swagger 확인 → 커밋(태스크 또는 논리 단위마다).

### Parallel Opportunities

- Setup T001~T007 전부 병렬.
- Foundational 테스트 T008~T013 병렬; 구현 T016/T017/T018/T020/T022 병렬(T014→T015 순차, T019 는 T018 후, T021 독립).
- US3 T039/T040/T041 병렬 후 T042·T043.
- US4 T044~T047 테스트 병렬, T048/T049 병렬 후 T050→T051, T052.
- US5·US6 는 음식 스토리와 파일이 겹치지 않아 별도 작업자 병렬 가능.

---

## Parallel Example: Foundational

```bash
# Red 먼저 — 6개 테스트 파일 동시 작성
Task: "JwtRefreshTokenRoleTest.kt"  Task: "AuthAdminIdArgumentResolverTest.kt"  Task: "JwtAuthenticationFilterAdminAttributeTest.kt"
Task: "FoodContentValidatorTest.kt"  Task: "FoodTransitionTest.kt"  Task: "AdminAuditRecorderTest.kt"
# Green — 서로 다른 파일
Task: "T016 AuthAdminId 리졸버"  Task: "T017 페이지 인터셉터 속성"  Task: "T018 감사 로그 엔티티/리포"  Task: "T020 검증기"  Task: "T022 잠금 seam"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 → Phase 2 → Phase 3(US1). **여기서 멈추고** quickstart Q1~Q4 검증 — React 팀이 로그인·감사 조회부터 붙일 수 있다.

### Incremental Delivery (PR 분할 제안)

- **PR-A**: Phase 1~3 + US2 + US7 (자격·감사·검수·전이·구 화면 정합 — 사고 위험 1·2·3 해소). 마이그레이션 3개 전부 포함(뒤 PR 이 스키마를 다시 만들지 않게).
- **PR-B**: US3 + US4 (탐색·파이프라인 개입 — 사고 위험 7·8).
- **PR-C**: US5 + US6 (회원 — 사고 위험 6) + Polish.

각 PR 은 기존 관리자 테스트 20개 + 신규 테스트 그린, `./gradlew build` 통과가 머지 조건.

---

## Notes

- 관리자 컨트롤러는 `@AuthAdminId adminId: Long` 을 받아 감사 조작자로 넘긴다. 랭체인 콜백(`content-reviews`·`contents`)은 조작자 0(시스템).
- 네이티브 SQL 정렬 컬럼은 화이트리스트 매핑(`sort` 문자열을 SQL 에 직접 붙이지 않는다).
- `@SQLRestriction` 우회는 네이티브 쿼리에서만 — 삭제/탈퇴 행을 엔티티로 로드하지 않고 프로젝션으로 다룬다(복구는 UPDATE 후 재조회).
- 마이그레이션 파일명 시각은 각 파일 생성 시점 로컬 시각(세 파일은 서로 순서 의존 없음).
- Kotlin 소스 주석 금지 — 설계 근거는 research.md·ADR·위키에.
