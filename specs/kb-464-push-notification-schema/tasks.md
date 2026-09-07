# Tasks: 푸시 알림 데이터 기반 (기기 토큰·알림 설정·알림 이력·발송 추적)

**Input**: Design documents from `specs/kb-464-push-notification-schema/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (contracts/ 없음 — 외부 인터페이스 없는 영속 계층 기능)

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 `:common` 리포지토리 테스트(BehaviorSpec)를 먼저 써서 Red 를 확인한 뒤 엔티티·리포지토리로 Green 을 만든다. `:common` 테스트 컨텍스트(`CommonTestApp`)는 Flyway 가 아니라 Hibernate `schema-generation=create` 로 엔티티에서 스키마를 만들므로, **마이그레이션 SQL 의 Red/Green 은 `:api` 통합 컨텍스트(Flyway on + `ddl-auto=validate`)** 가 담당한다.

**Organization**: 스토리별로 엔티티·리포지토리·테스트를 묶었다. 마이그레이션은 한 파일이지만 스토리마다 해당 테이블 DDL 을 같은 파일에 추가하는 방식으로 진행한다(파일명은 T004 에서 한 번 정한다).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·미완료 태스크 의존 없음 → 병렬 가능
- **[Story]**: US1 기기 토큰 / US2 알림 설정 / US3 알림 이력·발송 추적

## Path Conventions

- 엔티티·enum·값 객체: `common/src/main/kotlin/com/kbap/common/domain/notification/model/`
- 리포지토리: `common/src/main/kotlin/com/kbap/common/domain/notification/`
- 리포지토리 테스트: `common/src/test/kotlin/com/kbap/common/domain/notification/`
- 마이그레이션: `api/src/main/resources/db/migration/`
- api 테스트 부속: `api/src/test/kotlin/com/kbap/api/`

---

## Phase 1: Setup (공용 vocabulary)

**Purpose**: 세 스토리가 공유하는 enum·값 객체. 파일이 전부 다르므로 병렬.

- [ ] T001 [P] `DevicePlatform` enum(IOS, ANDROID) 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/model/DevicePlatform.kt`
- [ ] T002 [P] `NotificationType` enum(HELPFUL, NUDGE, REVIEW_REMINDER, NOTICE) 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationType.kt`
- [ ] T003 [P] `NotificationPreferences(helpful = true, reviewReminder = true, marketing = false, marketingConsentVersion: String? = null)` data class + `DEFAULT` 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationPreferences.kt`

---

## Phase 2: Foundational (마이그레이션 파일·경계 등록)

**Purpose**: 모든 스토리가 같은 마이그레이션 파일과 ArchUnit 허용 맵에 의존한다. 파일을 먼저 만들어 두고 각 스토리가 자기 테이블 DDL 을 추가한다.

- [ ] T004 마이그레이션 파일 생성(현재 로컬 시각으로 명명, 예: `date +%Y.%m.%d.%H.%M.%S`) — `api/src/main/resources/db/migration/V<timestamp>__push_notification_tables.sql`. 파일 상단에 한 줄 주석(용도·테이블 4개 이름)만 두고 DDL 은 각 스토리 태스크에서 추가
- [ ] T005 `ModuleBoundaryTest.allowedDomainDeps` 에 `"notification" to emptySet()` 추가 — `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt` (이 맵은 발견된 컨텍스트 집합과 정확 일치를 요구하므로 첫 엔티티가 생기는 순간 Red 가 된다 → 선반영)

**Checkpoint**: `./gradlew :common:compileKotlin` 통과, 마이그레이션 파일 존재(아직 DDL 없음)

---

## Phase 3: User Story 1 - 기기 단위 푸시 토큰 보관과 회원 연결 (Priority: P1) 🎯 MVP

**Goal**: `notification_device` — 기기 설치 식별자 유일, 회원 nullable 연결·해제, 재등록 갱신, 회원 기준 다기기 조회, 게스트 설정 JSON 왕복.

**Independent Test**: `NotificationDeviceJpaRepositoryTest` 만으로 spec US1 시나리오 #1~#6 과 US2 #5(게스트 설정 왕복) 검증. api 통합 컨텍스트 기동으로 DDL 정합 검증.

### Tests for User Story 1 (Test-First — 먼저 작성, 실패 확인) ⚠️

- [ ] T006 [US1] `NotificationDeviceJpaRepositoryTest`(BehaviorSpec, `@SpringBootTest` + `@Import(MySqlContainerConfig::class)`) 작성 — `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationDeviceJpaRepositoryTest.kt`. given/when/then(한국어): ① `register` 저장 후 `findByInstallationId` 로 1건, memberId null ② 같은 installationId 로 두 번째 `save` 시 `DataIntegrityViolationException`(유니크) ③ `linkMember` → `unlinkMember` 후 토큰·lang·guestSettings 유지 ④ `renew` 로 expoToken 교체 시 id 불변 ⑤ 회원 하나에 기기 2건 → `findByMemberId` 2건 ⑥ `guestSettings = NotificationPreferences(marketing = true, marketingConsentVersion = "v2")` 저장 후 재조회 동등 ⑦ `delete()` 후 `findByInstallationId` null(소프트삭제 필터). 실행: `./gradlew :common:test` → 컴파일 실패(Red) 확인

### Implementation for User Story 1

- [ ] T007 [US1] `NotificationDevice` 엔티티 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationDevice.kt`. `@Entity @Table(name = "notification_device", uniqueConstraints = [UniqueConstraint(name = "uk_notification_device_installation", columnNames = ["installation_id"])])`, `BaseEntity` 상속. 컬럼: `installation_id` VARCHAR(36) NOT NULL, `member_id` BIGINT NULL, `expo_token` VARCHAR(255) NOT NULL, `platform` `@Enumerated(STRING)` `columnDefinition = "ENUM('IOS','ANDROID')"`, `lang` VARCHAR(10) NOT NULL, `guest_settings` `@JdbcTypeCode(SqlTypes.JSON)` `NotificationPreferences?`. 메서드: `linkMember(memberId)`, `unlinkMember()`, `renew(expoToken, platform, lang, guestSettings)`, companion `register(...)`. 전 필드 기본값(no-arg 생성), 주석 없음
- [ ] T008 [US1] `NotificationDeviceJpaRepository : JpaRepository<NotificationDevice, Long>` 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationDeviceJpaRepository.kt`. `findByInstallationId(installationId: String): NotificationDevice?`, `findByMemberId(memberId: Long): List<NotificationDevice>`, `findByExpoToken(expoToken: String): List<NotificationDevice>`. `./gradlew :common:test` Green 확인
- [ ] T009 [US1] 마이그레이션에 `notification_device` DDL 추가 — T004 파일. `community_post` 스타일(소문자, 백틱 없음): id BIGINT AUTO_INCREMENT PK, installation_id VARCHAR(36) NOT NULL, member_id BIGINT NULL, expo_token VARCHAR(255) NOT NULL, platform ENUM('IOS','ANDROID') NOT NULL, lang VARCHAR(10) NOT NULL, guest_settings JSON NULL, status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE', created_at/updated_at DATETIME(6) NOT NULL, `UNIQUE KEY uk_notification_device_installation (installation_id)`, `KEY idx_notification_device_member (member_id)`, `CONSTRAINT fk_notification_device_member FOREIGN KEY (member_id) REFERENCES member (id)`
- [ ] T010 [US1] `TestTables.tables` 에 `"notification_device"` 추가(FK 자식인 `notification_dispatch`·`notification` 보다 뒤, `member` 보다 앞) — `api/src/test/kotlin/com/kbap/api/TestTables.kt`. `./gradlew :api:test` 로 Flyway 적용 + `ddl-auto=validate` 통과 확인(엔티티 컬럼 길이·타입이 DDL 과 어긋나면 여기서 Red)

**Checkpoint**: US1 시나리오 전부 Green, api 컨텍스트 validate 통과, ArchUnit `arch` 태그 통과

---

## Phase 4: User Story 2 - 회원 알림 설정 보관과 광고성 수신 동의 기록 (Priority: P2)

**Goal**: `notification_setting` — 회원당 1건, 기본값 해석, 광고성 수신 off→on 시 동의 시각·문구 버전 서버 기록, on→off 시 해제, 발송 가능 판정.

**Independent Test**: `NotificationSettingJpaRepositoryTest` 만으로 spec US2 #1~#4·#6 검증(게스트 설정 #5 는 US1 에서 완료).

### Tests for User Story 2 (Test-First) ⚠️

- [ ] T011 [US2] `NotificationSettingJpaRepositoryTest` 작성 — `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepositoryTest.kt`. ① `findByMemberId` 없음 → null, `NotificationSetting.defaultFor(memberId).preferences() == NotificationPreferences.DEFAULT` ② `updateMarketing(true, "v2", now)` → `marketingOptInAt == now`, `marketingConsentVersion == "v2"` ③ 이어서 `updateMarketing(false, null, later)` → 둘 다 null ④ 같은 값 재호출 시 시각 불변 ⑤ 같은 memberId 두 번째 save → 유니크 위반 ⑥ `isMarketingAllowed("v2")`: on+v2 → true, on+v1 → false, off → false. Red 확인

### Implementation for User Story 2

- [ ] T012 [US2] `NotificationSetting` 엔티티 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationSetting.kt`. `@Table(name = "notification_setting", uniqueConstraints = [UniqueConstraint(name = "uk_notification_setting_member", columnNames = ["member_id"])])`. 컬럼: `member_id` BIGINT NOT NULL, `helpful`·`review_reminder` Boolean NOT NULL 기본 true, `marketing` Boolean NOT NULL 기본 false, `marketing_opt_in_at` `LocalDateTime?`, `marketing_consent_version` VARCHAR(20) NULL. 메서드: `preferences()`, `updateHelpful`, `updateReviewReminder`, `updateMarketing(enabled, consentVersion, now)`, `isMarketingAllowed(requiredVersion)` (버전 비교는 문자열 자연 정렬 `compareTo` — "v1" < "v2"), companion `defaultFor(memberId)`
- [ ] T013 [US2] `NotificationSettingJpaRepository` 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepository.kt`. `findByMemberId(memberId: Long): NotificationSetting?`. `:common:test` Green
- [ ] T014 [US2] 마이그레이션에 `notification_setting` DDL 추가 — T004 파일. member_id BIGINT NOT NULL, helpful BOOLEAN NOT NULL DEFAULT TRUE, review_reminder BOOLEAN NOT NULL DEFAULT TRUE, marketing BOOLEAN NOT NULL DEFAULT FALSE, marketing_opt_in_at DATETIME(6) NULL, marketing_consent_version VARCHAR(20) NULL, 공통 컬럼, `UNIQUE KEY uk_notification_setting_member (member_id)`, `CONSTRAINT fk_notification_setting_member FOREIGN KEY (member_id) REFERENCES member (id)`
- [ ] T015 [US2] `TestTables.tables` 에 `"notification_setting"` 추가(`member` 앞) — `api/src/test/kotlin/com/kbap/api/TestTables.kt`. `:api:test` validate 통과 확인

**Checkpoint**: US2 Green, validate 통과

---

## Phase 5: User Story 3 - 알림 이력과 발송 추적 보관 (Priority: P3)

**Goal**: `notification`(알림함 단위, 회원/기기 수신자, 읽음 처리, keyset 페이지, 미읽음 수, 전체 읽음) + `notification_dispatch`(알림 1 : 발송 N, 상태 전이, 영수증 조회 대상 스캔).

**Independent Test**: `NotificationJpaRepositoryTest`·`NotificationDispatchJpaRepositoryTest` 만으로 spec US3 #1~#5 검증.

### Tests for User Story 3 (Test-First) ⚠️

- [ ] T016 [P] [US3] `NotificationJpaRepositoryTest` 작성 — `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationJpaRepositoryTest.kt`. ① 회원 알림 3건 저장 → `findPageByMemberId(memberId, cursor = null, PageRequest.of(0, 20))` 최신순(id desc) 3건, 필드(type·title·body·data·readAt null) 확인 ② 25건 저장 후 size 20 첫 페이지 → 마지막 id 를 cursor 로 두 번째 페이지 5건, 중복·누락 없음 ③ `markRead(now)` 1건 후 `countByMemberIdAndReadAtIsNull` == 2, 재호출 시 readAt 불변 ④ `markAllReadByMemberId(memberId, now)` 반환 2, 이후 count 0 ⑤ `Notification.forInstallation("uuid", NOTICE, ...)` 저장 성공(memberId null) ⑥ `data = mapOf("type" to "REVIEW_REMINDER", "foodId" to "12", "notificationId" to 34L)` 왕복 후 값 타입 유지
- [ ] T017 [P] [US3] `NotificationDispatchJpaRepositoryTest` 작성 — `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationDispatchJpaRepositoryTest.kt`. ① 알림 1건에 dispatch 2건(기기 2대) 저장 → `findByNotificationId` 2건, expoToken 스냅샷 확인 ② `markSent("ticket-1")` PENDING→SENT, `markDelivered()` SENT→DELIVERED, `markFailed("DeviceNotRegistered")` → FAILED + error ③ PENDING 에서 `markDelivered()` 는 `IllegalStateException` ④ `findByDispatchStatusAndCreatedAtBefore(SENT, now.minusMinutes(15), pageable)` 가 15분 지난 SENT 만 반환

### Implementation for User Story 3

- [ ] T018 [P] [US3] `NotificationDispatchStatus` enum(PENDING, SENT, DELIVERED, FAILED) — `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationDispatchStatus.kt`
- [ ] T019 [P] [US3] `Notification` 엔티티 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/model/Notification.kt`. `@Table(name = "notification")`. 컬럼: `member_id` BIGINT NULL, `installation_id` VARCHAR(36) NULL, `type` `@Enumerated(STRING)` VARCHAR(30) NOT NULL, `title` VARCHAR(200) NOT NULL, `body` VARCHAR(1000) NOT NULL, `data` `@JdbcTypeCode(SqlTypes.JSON)` `Map<String, Any>?`, `read_at` `LocalDateTime?`. 메서드 `markRead(now)`(멱등), `isRead()`, companion `forMember(...)`, `forInstallation(...)`
- [ ] T020 [US3] `NotificationDispatch` 엔티티 작성(T018 의존) — `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationDispatch.kt`. `@Table(name = "notification_dispatch")`. 컬럼: `notification_id` BIGINT NOT NULL, `notification_device_id` BIGINT NULL, `expo_token` VARCHAR(255) NOT NULL, `ticket_id` VARCHAR(64) NULL, `dispatch_status` `@Enumerated(STRING)` `columnDefinition = "ENUM('PENDING','SENT','DELIVERED','FAILED') default 'PENDING'"`, `error` VARCHAR(255) NULL. 메서드 `markSent(ticketId)`, `markDelivered()`, `markFailed(error)` — 허용되지 않는 전이는 `IllegalStateException`
- [ ] T021 [P] [US3] `NotificationJpaRepository` 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationJpaRepository.kt`. `@Query("select n from Notification n where n.memberId = :memberId and (:cursor is null or n.id < :cursor) order by n.id desc") findPageByMemberId(memberId, cursor: Long?, pageable): List<Notification>`, `countByMemberIdAndReadAtIsNull(memberId): Long`, `@Modifying @Query("update Notification n set n.readAt = :now where n.memberId = :memberId and n.readAt is null") markAllReadByMemberId(memberId, now): Int`
- [ ] T022 [P] [US3] `NotificationDispatchJpaRepository` 작성 — `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationDispatchJpaRepository.kt`. `findByNotificationId(notificationId: Long): List<NotificationDispatch>`, `findByDispatchStatusAndCreatedAtBefore(status: NotificationDispatchStatus, before: LocalDateTime, pageable: Pageable): List<NotificationDispatch>`. `:common:test` Green
- [ ] T023 [US3] 마이그레이션에 `notification`·`notification_dispatch` DDL 추가 — T004 파일. notification: member_id BIGINT NULL, installation_id VARCHAR(36) NULL, type VARCHAR(30) NOT NULL, title VARCHAR(200) NOT NULL, body VARCHAR(1000) NOT NULL, data JSON NULL, read_at DATETIME(6) NULL, 공통 컬럼, `KEY idx_notification_member_id (member_id, id)`, `KEY idx_notification_installation_id (installation_id, id)`, `CONSTRAINT fk_notification_member FOREIGN KEY (member_id) REFERENCES member (id)`. notification_dispatch: notification_id BIGINT NOT NULL, notification_device_id BIGINT NULL, expo_token VARCHAR(255) NOT NULL, ticket_id VARCHAR(64) NULL, dispatch_status ENUM('PENDING','SENT','DELIVERED','FAILED') NOT NULL DEFAULT 'PENDING', error VARCHAR(255) NULL, 공통 컬럼, `KEY idx_notification_dispatch_status_created (dispatch_status, created_at)`, `KEY idx_notification_dispatch_notification (notification_id)`, `CONSTRAINT fk_notification_dispatch_notification FOREIGN KEY (notification_id) REFERENCES notification (id)`
- [ ] T024 [US3] `TestTables.tables` 맨 앞에 `"notification_dispatch"`, `"notification"` 추가(자식 우선) — `api/src/test/kotlin/com/kbap/api/TestTables.kt`. `:api:test` validate 통과 확인

**Checkpoint**: US3 Green, 테이블 4개 전부 validate 통과

---

## Phase 6: Polish & Cross-Cutting

- [ ] T025 quickstart.md 3절대로 로컬 `SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun` 부팅 → Flyway 적용 로그 확인 + `SHOW CREATE TABLE` 4개로 유니크·FK·인덱스 이름이 data-model.md 와 일치하는지 대조
- [ ] T026 `./gradlew build` 전체 통과(ArchUnit `arch` 태그 포함). Kotlin 소스에 주석이 없는지, `@Column(length)`·`columnDefinition` 이 DDL 과 1:1 인지 최종 대조
- [ ] T027 Jira KB-464 DoD 체크 + 지식 위키 `../kbap-agenthub/wiki/` 에 광고성 수신 동의 모델(marketing 카테고리·문구 버전·야간 보류) 기록, `INDEX.md` 한 줄 추가

---

## Dependencies & Execution Order

```
Phase 1 (T001~T003, 병렬)
  └─ Phase 2 (T004, T005)
       ├─ US1 (T006 → T007 → T008 → T009 → T010)
       ├─ US2 (T011 → T012 → T013 → T014 → T015)     # US1 과 독립
       └─ US3 (T016,T017 병렬 → T018,T019 병렬 → T020 → T021,T022 병렬 → T023 → T024)  # US1·US2 와 독립
            └─ Phase 6 (T025 → T026 → T027)
```

- US1·US2·US3 는 서로 다른 파일만 만지므로 이론상 병렬 가능하나, **마이그레이션 파일(T004)은 셋이 공유**한다 — 한 세션에서 순서대로 진행하는 것을 권장.
- `TestTables` 순서: `notification_dispatch` → `notification` → `notification_device` → `notification_setting` → (기존) … → `member`.

## Parallel Example

```
# Phase 1
T001 DevicePlatform | T002 NotificationType | T003 NotificationPreferences

# US3 테스트 2개 동시 작성
T016 NotificationJpaRepositoryTest | T017 NotificationDispatchJpaRepositoryTest

# US3 구현
T018 NotificationDispatchStatus | T019 Notification   →  T020 NotificationDispatch  →  T021 | T022
```

## Implementation Strategy

- **MVP = Phase 1 + 2 + US1**: `notification_device` 하나만 있어도 토큰 API(KB-465)가 시작될 수 있고 FE 병렬 작업의 기준점이 된다.
- US2·US3 는 각각 독립 증분. 한 PR 에 셋을 다 담되 커밋은 스토리 단위로 나눈다(`feat(notification): …`).
- 각 스토리에서 테스트 태스크(T006·T011·T016·T017)는 반드시 구현보다 먼저 커밋한다 — Red 커밋이 헌법 원칙 I 의 증거다.
