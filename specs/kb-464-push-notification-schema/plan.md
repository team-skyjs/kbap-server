# Implementation Plan: 푸시 알림 데이터 기반 (기기 토큰·알림 설정·알림 이력·발송 추적)

**Branch**: `kb-464-push-notification-schema` | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-464-push-notification-schema/spec.md` (Jira KB-464, 에픽 KB-463)

## Summary

푸시 알림 전체(에픽 KB-463)의 저장 기반을 만든다. `com.kbap.common.domain.notification` 컨텍스트를 신설해 엔티티 4개(`NotificationDevice`·`NotificationSetting`·`Notification`·`NotificationDispatch`)와 리포지토리 4개를 두고, Flyway 마이그레이션 1개로 테이블 4개를 만든다. API·배치·발송 로직은 후속 태스크(KB-465~474)가 이 위에 쌓는다. 이 기능은 **영속 계층만** 제공하며 HTTP 계약·외부 seam 은 없다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (data-jpa, Hibernate 7 — `@JdbcTypeCode(SqlTypes.JSON)`), Flyway(+mysql), Kotest BehaviorSpec + kotest-extensions-spring

**Storage**: MySQL 8 (prod RDS, 테스트는 Testcontainers MySQL). 스키마 owner = `:api` Flyway. batch 는 flyway off 로 같은 스키마 공유

**Testing**: `:common` 리포지토리 테스트 — `@SpringBootTest` + `@Import(MySqlContainerConfig::class)`(부트 클래스 `CommonTestApp`), `:api` 통합 컨텍스트에서 `ddl-auto=validate` 로 엔티티↔스키마 정합 검증

**Target Platform**: Linux 컨테이너(ECS EC2), 공유 RDS

**Project Type**: 모듈러 모놀리스 백엔드 — 이 기능은 `:common` 영속 계층 + `:api` 마이그레이션 리소스 + `:api` 테스트 정리 목록

**Performance Goals**: 회원 기준 알림 목록 keyset 페이지 조회와 미읽음 카운트가 인덱스 범위 스캔으로 끝날 것(테이블 규모 수십만 행 가정). 기기 식별자 upsert 는 유니크 인덱스 단건 조회

**Constraints**: 엔티티 간 JPA 연관관계 금지(id 값 참조), FK 는 Flyway 가 강제, 모든 엔티티 `BaseEntity` 상속(id IDENTITY·status 소프트삭제·시각), 컬럼 길이 MySQL 기준 명시, 마이그레이션은 timestamp 버전·순서 비의존, Kotlin 주석 금지

**Scale/Scope**: 테이블 4개·엔티티 4개·enum 3개·값 객체 1개·리포지토리 4개·마이그레이션 1개·리포지토리 테스트 4개·ArchUnit 맵 1줄·TestTables 4줄

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 게이트 | 판정 |
|---|---|---|
| I. Test-First | 리포지토리 테스트(BehaviorSpec)를 먼저 작성해 Red(컴파일 실패) 확인 후 엔티티·리포지토리로 Green. `:common` 테스트는 Hibernate `schema-generation=create` 라 마이그레이션의 Red/Green 은 `:api` 통합 컨텍스트(Flyway on + `ddl-auto=validate`)가 담당 | PASS — tasks 에서 테스트 태스크가 구현 태스크에 선행 |
| II. Bounded Contexts | 새 컨텍스트 `common.domain.notification` 신설. 다른 컨텍스트(member·food·order·review)는 **Long id 값**으로만 참조, 엔티티 타입 import 없음. `ModuleBoundaryTest` 허용 맵에 `"notification" to emptySet()` 추가. 공유 vocabulary 는 `LanguageCode` 만 쓰지 않고 lang 을 문자열로 저장(§research R3) | PASS |
| III. Dependency Direction | 변경 범위가 `:common`(엔티티·리포지토리)과 `:api` 리소스(Flyway SQL)·테스트뿐. `:common` 이 다른 모듈을 의존하지 않음. Spring-free 커널(`common.core`) 무변경 | PASS |
| IV. Persistence Ownership | 엔티티·리포지토리는 `common.domain.notification`(model/) 에 public. JPA 연관관계 없음. FK·유니크는 마이그레이션이 강제. 도메인 메서드(읽음 처리·회원 연결/해제·광고성 수신 동의 스탬프·문구 버전)는 엔티티에 둠. 트랜잭션 경계는 이 기능에서 소비자가 없어 해당 없음(후속 API·배치가 선언) | PASS |
| V. Language Policy | 알림 제목·본문은 발송 시점 렌더 문자열을 저장. `lang` 은 기기에서 흘러든 값이라 저장 시 검증·정규화하지 않고 그대로 보관, 미지원 코드 폴백(en)은 발송 단계(KB-468) 책임 | PASS |

위반 없음 → Complexity Tracking 비움.

## Project Structure

### Documentation (this feature)

```text
specs/kb-464-push-notification-schema/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정 8건
├── data-model.md        # Phase 1 — 테이블·엔티티·인덱스·도메인 메서드
├── quickstart.md        # Phase 1 — 검증 절차
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 가 생성
```

`contracts/` 는 만들지 않는다 — 이 기능은 외부 인터페이스(HTTP·seam)를 노출하지 않는다.

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/notification/
├── NotificationDeviceJpaRepository.kt
├── NotificationSettingJpaRepository.kt
├── NotificationJpaRepository.kt
├── NotificationDispatchJpaRepository.kt
└── model/
    ├── NotificationDevice.kt
    ├── DevicePlatform.kt
    ├── NotificationPreferences.kt        # 값 객체 — 회원 설정·게스트 설정 공용
    ├── NotificationSetting.kt
    ├── Notification.kt
    ├── NotificationType.kt
    ├── NotificationDispatch.kt
    └── NotificationDispatchStatus.kt

common/src/test/kotlin/com/kbap/common/domain/notification/
├── NotificationDeviceJpaRepositoryTest.kt
├── NotificationSettingJpaRepositoryTest.kt
├── NotificationJpaRepositoryTest.kt
└── NotificationDispatchJpaRepositoryTest.kt

api/src/main/resources/db/migration/
└── V2026.09.07.HH.mm.ss__push_notification_tables.sql   # 생성 시각으로 명명

api/src/test/kotlin/com/kbap/api/TestTables.kt                 # clearAll 목록에 4개 추가 (FK 순서: notification_dispatch → notification → notification_device → notification_setting)
api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt   # allowedDomainDeps 에 "notification" to emptySet()
```

**Structure Decision**: 기존 컨텍스트 배치(`common.domain.<ctx>` 루트에 리포지토리, `model/` 에 엔티티·enum·값 객체)를 그대로 따른다. 도메인 서비스는 만들지 않는다 — 이 기능에는 소비자가 없고, 토큰 upsert·설정 전환 같은 정책은 KB-465·466 의 api 기능 패키지 서비스가 소유한다(창구 서비스 금지 원칙).

## Phase 0 → [research.md](research.md)

## Phase 1 → [data-model.md](data-model.md), [quickstart.md](quickstart.md)

## Post-Design Constitution Re-check

data-model 확정 후 재검토: 엔티티 4개 모두 `BaseEntity` 상속, 연관관계 없음(참조 컬럼 `member_id`·`notification_id` 는 `Long`), FK 3개·유니크 2개는 마이그레이션에만 존재, 새 컨텍스트는 다른 도메인 타입을 import 하지 않음. 원칙 I~V 모두 PASS 유지.

## Complexity Tracking

위반 없음.
