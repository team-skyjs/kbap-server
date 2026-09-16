# Tasks: 회원 알림함 API — 최근 7일 알림 목록·읽음 처리

**Input**: Design documents from `specs/kb-467-notification-inbox-api/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/notification-inbox-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 통합 테스트 1클래스(`NotificationInboxControllerTest`, 기존 `@IntegrationTest` 컨텍스트)를 스토리별로 먼저 쓰고 Red 를 확인한 뒤 구현한다. 새 Spring 컨텍스트 금지.

**Organization**: US1(목록)·US2(읽음)이 같은 파일들(테스트 클래스·컨트롤러·서비스·Api)을 건드리므로 **순차** 실행. 공유 부품(ErrorCode·DTO·WebConfig)은 Foundational 로 뺀다.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- api: `api/src/main/kotlin/com/kbap/api/notification/`, 테스트 `api/src/test/kotlin/com/kbap/api/notification/`
- common: `common/src/main/kotlin/com/kbap/common/`

---

## Phase 1: Setup

없음 — 새 의존·마이그레이션·디렉터리 없음.

---

## Phase 2: Foundational (두 스토리가 공유하는 부품)

- [x] T001 [P] `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `NOTIFICATION_NOT_FOUND("NOTIFICATION-002", 404, "해당 알림을 찾을 수 없습니다")` 추가 — `MARKETING_CONSENT_REQUIRED` 바로 아래. (`ErrorCodeStatusTest` 가 형식·유일성 검사)
- [x] T002 [P] `api/src/main/kotlin/com/kbap/api/notification/NotificationResponse.kt` 생성 — `data class NotificationResponse(id: Long, title: String, body: String, receivedAt: Long, read: Boolean)` + `companion fun from(n: Notification) = NotificationResponse(n.id, n.title, n.body, n.createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), n.readAt != null)` — `ReviewResponse`·`Order.orderedAt()` 과 같은 epoch 밀리초 변환.
- [x] T003 [P] `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` — `jwtAuthenticationFilterRegistration` 의 `addUrlPatterns` 에서 `"${ApiPaths.API}/notifications/settings"` 를 `"${ApiPaths.API}/notifications"`, `"${ApiPaths.API}/notifications/*"` 두 줄로 교체. `guestExemptions` 에 `GuestExemption("PUT", Regex("^${ApiPaths.API}/notifications/tokens$"))` 추가(비회원 알림 제거 후속에서 함께 삭제 — 지금은 `NotificationTokenControllerTest` 게스트 시나리오를 깨지 않기 위한 최소 조치).
- [x] T004 `api/src/test/kotlin/com/kbap/api/notification/NotificationInboxControllerTest.kt` 골격 생성 — `@IntegrationTest` + `BehaviorSpec` + `SpringExtension`; `@Autowired MockMvc`, `DataSource`, `NotificationJpaRepository`, `FakeSocialTokenVerifier`; `jacksonObjectMapper()`; `beforeSpec { TestTables.clearAll(dataSource); fakeSocialTokenVerifier.reset() }`; 헬퍼 `login(sub): Pair<Long, String>`(`NotificationSettingControllerTest` 와 동일 — `POST /api/auth/login` + `SELECT id FROM member WHERE provider_uid`), `seed(memberId, title, body = "b", type = NotificationType.NOTICE): Notification`(`repository.save(Notification.forMember(...))`), `ageTo8DaysAgo(id)`(`UPDATE notification SET created_at = ? WHERE id = ?` JDBC), `list(token?)`, `read(token?, id)`. 아직 시나리오 없음 — 컴파일만.

**Checkpoint**: `./gradlew :api:compileTestKotlin` 통과. 기존 `NotificationTokenControllerTest`·`NotificationSettingControllerTest` Green(경로 변경 회귀 없음).

---

## Phase 3: User Story 1 - 최근 7일 알림을 최신순으로 본다 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/notifications` 가 본인의 168시간 이내 알림을 id 역순으로 전부 반환한다.

**Independent Test**: 7일 이내 5건 + 8일 전 2건 시드 → 5건만 역순. 타인 알림 미포함. 미인증 401.

### Tests for User Story 1 (Test-First — 먼저 쓰고 반드시 FAIL 확인) ⚠️

- [x] T005 [US1] `NotificationInboxControllerTest` 에 `given("최근 7일 알림 목록")` 추가 — when/then 6개(spec US1 AC): (1) 알림 없음 → `payload == []`; (2) 이내 5건 + 8일 전 2건(`ageTo8DaysAgo`) → 5건, `id` 내림차순; (3) HELPFUL·NOTICE·SCAN_SUGGESTION 섞어 3건 → 3건 시간 역순(응답에 `type` 필드 없음, `receivedAt` 이 숫자(epoch ms)이며 DB `created_at` 을 같은 변환으로 계산한 값과 일치); (4) 회원 A·B 각각 시드 → A 조회에 B 것 없음; (5) 1건 읽음(`readAt` 세팅 후 save) + 1건 안 읽음 → `read` true/false 각각, 정렬 불변; (6) 토큰 없음 → 401.
- [x] T006 [US1] `./gradlew :api:test` → 6개 전부 Red(404 — 매핑 없음 / 401 은 T003 로 이미 Green 일 수 있음, 나머지 5개 Red 확인).

### Implementation for User Story 1

- [x] T007 [US1] `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationJpaRepository.kt` 에 `fun findByMemberIdAndCreatedAtAfterOrderByIdDesc(memberId: Long, since: LocalDateTime): List<Notification>` 추가.
- [x] T008 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationInboxService.kt` 생성 — `@Service class NotificationInboxService(notificationRepository, memberService)`; `@Transactional(readOnly = true) fun getRecentNotifications(memberId: Long): List<NotificationResponse>` = `memberService.getMember(memberId)` → `repository.findByMemberIdAndCreatedAtAfterOrderByIdDesc(memberId, LocalDateTime.now().minusDays(7)).map(NotificationResponse::from)`. 상수 `RECENT_DAYS = 7L`.
- [x] T009 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationInboxApi.kt` 생성 — `@Tag(name = "Notification")`, `@SecurityRequirement`, `getRecentNotifications(memberId: Long): ResponseEntity<BaseResponse<List<NotificationResponse>>>` 에 `@Operation`(최근 7일·최신순·페이징 없음 명시)·`@ApiResponses`(200·401).
- [x] T010 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationInboxController.kt` 생성 — `@RestController @RequestMapping(ApiPaths.API + "/notifications")`, `@GetMapping override fun getRecentNotifications(@AuthMemberId memberId: Long)` → `ResponseEntity.ok(BaseResponse.ok(service.getRecentNotifications(memberId)))`.
- [x] T011 [US1] `./gradlew :api:test` → US1 6개 Green, 기존 스펙 회귀 없음.

**Checkpoint**: 커밋 `feat(notification): 회원 최근 7일 알림 목록 조회`.

---

## Phase 4: User Story 2 - 알림 하나를 읽음 처리한다 (Priority: P2)

**Goal**: `PATCH /api/notifications/{id}/read` 가 본인 알림을 멱등으로 읽음 처리하고, 타인·부재·삭제는 404 `NOTIFICATION-002`.

**Independent Test**: 안 읽은 알림 read → `read: true`, 재호출 동일·`readAt` 불변. 타인 id·없는 id → 404 + 코드. 8일 전 알림도 성공. 미인증 401.

### Tests for User Story 2 (Test-First — 먼저 쓰고 반드시 FAIL 확인) ⚠️

- [x] T012 [US2] `NotificationInboxControllerTest` 에 `given("알림 읽음 처리")` 추가 — when/then 6개(spec US2 AC): (1) 안 읽은 알림 read → 200, `payload.read == true`, 이후 목록에서도 true; (2) 같은 id 재호출 → 200, DB `read_at` 값이 첫 호출과 동일(JDBC 로 읽어 비교); (3) B 의 알림 id 로 A 가 read → 404, `code == "NOTIFICATION-002"`, B 알림 `read_at` 여전히 null; (4) 없는 id(999999) → 404 같은 코드; (5) `ageTo8DaysAgo` 한 본인 알림 read → 200; (6) 토큰 없음 → 401.
- [x] T013 [US2] `./gradlew :api:test` → 6개 Red 확인(401 제외).

### Implementation for User Story 2

- [x] T014 [US2] `NotificationJpaRepository.kt` 에 `fun findByIdAndMemberId(id: Long, memberId: Long): Notification?` 추가.
- [x] T015 [US2] `NotificationInboxService.kt` 에 `@Transactional fun markRead(memberId: Long, notificationId: Long): NotificationResponse` = `memberService.getMember(memberId)` → `repository.findByIdAndMemberId(notificationId, memberId) ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)` → `notification.markRead(LocalDateTime.now())` → `NotificationResponse.from(notification)`. `save()` 호출 없음(dirty checking).
- [x] T016 [US2] `NotificationInboxApi.kt` 에 `markRead(memberId: Long, notificationId: Long)` 문서 추가 — `@Operation`(멱등·취소 없음), `@ApiResponses`(200·401·404 `NOTIFICATION-002`).
- [x] T017 [US2] `NotificationInboxController.kt` 에 `@PatchMapping("/{notificationId}/read") override fun markRead(@AuthMemberId memberId: Long, @PathVariable notificationId: Long)` 추가.
- [x] T018 [US2] `./gradlew :api:test` → US2 6개 Green, 전체 회귀 없음.

**Checkpoint**: 커밋 `feat(notification): 알림 읽음 처리`.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T019 `./gradlew build` 전체 Green(arch 태그 포함 — `ErrorCodeStatusTest`·`ModuleBoundaryTest`).
- [x] T020 [P] `git diff develop --stat` 로 변경 파일이 plan.md 의 목록(신규 5·변경 3 + specs)뿐인지 확인. Kotlin 소스 주석 0.
- [x] T021 [P] Jira KB-467 코멘트(ADF, `addCommentToJiraIssue`): "회원 전용(비회원 알림은 기획상 제거 예정) · 최근 7일만·페이징 없음 · 모두 읽기·읽음 취소·미읽음 수 없음 · 응답 id·제목·본문·수신 시각·읽음 여부 · 제목/본문 저장값 그대로". DoD 의 "게스트 결정 기록" 항목.
- [x] T022 draft PR → develop(`open-draft-pr-to-develop`): Jira 링크, 계약 요약, 게스트 예외 한 줄이 임시임을 명시.

---

## Dependencies & Execution Order

- Phase 2 (T001~T004) → US1 (T005~T011) → US2 (T012~T018) → Polish.
- T001·T002·T003 은 서로 다른 파일이라 병렬. T004 는 T002 를 참조하지 않으므로 병렬 가능하나 컴파일 확인은 셋 다 끝난 뒤.
- US2 는 US1 의 컨트롤러·서비스·Api 파일에 메서드를 추가하므로 US1 완료 후.

## Parallel Example: Foundational

```text
T001 ErrorCode · T002 NotificationResponse · T003 WebConfig — 동시 편집 가능
```

## Implementation Strategy

- MVP = Phase 2 + US1. 목록만으로도 앱이 알림함을 그릴 수 있다.
- US2 는 같은 세션에서 이어서. 둘 다 작아서 PR 하나.

## Notes

- **2026-09-08 통합**: 구현 후 사용자 지시로 `NotificationInbox{Controller,Api,Service}` 를 기존 설정 컨트롤러·서비스에 합쳤다 — `NotificationSetting{Controller,Api,Service}` → `Notification{Controller,Api,Service}` rename, 테스트는 `NotificationInboxTest`. 아래 T008~T010·T015~T017 의 파일명은 통합 전 기준.

- Kotlin 소스 주석 금지. 근거는 커밋 메시지·research.md.
- `@IntegrationTest` 외 조합 금지, `RANDOM_PORT` 금지(KB-392).
- 기존 `findPageByMemberId`·`markAllReadByMemberId`·`countByMemberIdAndReadAtIsNull` 은 건드리지 않는다.
- 게스트 예외 한 줄(T003)은 비회원 알림 제거 태스크에서 삭제 대상 — PR 본문에 명시.
