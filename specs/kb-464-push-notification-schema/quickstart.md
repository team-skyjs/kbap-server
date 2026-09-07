# Quickstart: 푸시 알림 데이터 기반 검증

## 1. Red 확인 (테스트 먼저)

리포지토리 테스트 4개를 `common/src/test/kotlin/com/kbap/common/domain/notification/` 에 작성한 뒤 실행한다. 엔티티가 없으면 컴파일 실패가 Red 다. `:common` 테스트 컨텍스트는 Flyway 가 아니라 Hibernate 가 엔티티에서 스키마를 만들므로(`schema-generation=create`), 마이그레이션 SQL 의 정합은 2단계의 `:api` 테스트가 검증한다.

```bash
./gradlew :common:test
```

Kotest 는 Gradle `--tests` 필터를 무시하므로 모듈 전체를 돌린다(컨테이너 1개 공유).

## 2. Green

엔티티 4개·enum 3개·값 객체 1개·리포지토리 4개 + 마이그레이션 SQL 을 추가하고 다시 `:common:test`. 이어서 api 통합 컨텍스트가 Flyway 로 새 테이블을 만들고 `ddl-auto=validate` 가 통과하는지 확인한다.

```bash
./gradlew :api:test
```

`ModuleBoundaryTest`(태그 `arch`) 가 `notification` 컨텍스트의 의존 방향(빈 집합)과 `@Entity` 위치를 검사한다. `TestTables.clearAll` 에 새 테이블을 넣지 않으면 다른 통합 테스트가 FK 로 막힌다.

## 3. 로컬 실기동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

부팅 로그에 `Migrating schema ... to version 2026.09.07...` 가 찍히고, 로컬 MySQL 에서 다음이 성립하면 끝.

```sql
SHOW CREATE TABLE notification_device\G          -- uk_notification_device_installation, fk_notification_device_member
SHOW CREATE TABLE notification_setting\G
SHOW CREATE TABLE notification\G        -- idx_notification_member_id (member_id, id)
SHOW CREATE TABLE notification_dispatch\G       -- fk_notification_dispatch_notification
```

## 4. 수용 시나리오 ↔ 테스트 매핑

| spec 시나리오 | 테스트 |
|---|---|
| US1 #1·#2·#3·#6 기기당 1건·회원 연결/해제·재등록 | `NotificationDeviceJpaRepositoryTest` — findByInstallationId, linkMember/unlinkMember, renew, 유니크 위반 |
| US1 #5 회원 기준 다기기 조회 | `NotificationDeviceJpaRepositoryTest` — findByMemberId 2건 |
| US2 #1~#4·#6 기본값·광고성 동의 스탬프·문구 버전·회원당 1건 | `NotificationSettingJpaRepositoryTest` — defaultFor, updateMarketing 전환, isMarketingAllowed 버전 비교, 유니크 위반 |
| US2 #5 게스트 설정 JSON 왕복 | `NotificationDeviceJpaRepositoryTest` — guestSettings 저장 후 재조회 동등 |
| US3 #1·#2 최신순 페이지·미읽음 수 | `NotificationJpaRepositoryTest` — findPageByMemberId 커서, countByMemberIdAndReadAtIsNull, markAllRead |
| US3 #3·#4 발송 추적 N건·실패 사유 | `NotificationDispatchJpaRepositoryTest` — findByNotificationId, 상태 전이, findByDispatchStatusAndCreatedAtBefore |
| US3 #5 게스트 대상 알림 저장 | `NotificationJpaRepositoryTest` — forInstallation 저장 |
