# Research: 알림 설정 기기별 분리 (KB-544)

Phase 0 산출물. spec 의 미확정 항목과 설계 갈림길을 코드 기준으로 확정한다. 코드 사실은 2026-09-11 `develop`(KB-543·KB-468 머지 후) 기준이다.

## 현재 코드 사실 (설계 전제)

- `NotificationSetting`(`common.domain.notification.model`): `member_id`·`activity`·`meal_time` 뿐. **소식(news) 토글 컬럼이 없다** — 현재 `news.enabled` 는 저장값이 아니라 회원 동의 두 종류가 열려 있는지로 계산한다(`NotificationConsentService.isMarketingEnabled`, 버전 무관 `ANY_CONSENT_VERSION = 0`).
- `NotificationSettingJpaRepository`: `findByMemberId(memberId): NotificationSetting?`(단건)·`findByMemberIdIn`. 소비자는 `NotificationService`(api)·`PushTargetResolver`(common).
- `PushTargetResolver.resolve(memberIds, type)`: 기기는 `notification_device.member_id in (...)` + 토큰 유효, 토글은 **회원 단위** 설정 행으로 판정. `HELPFUL`·`REVIEW_REMINDER` → `activity`, `MEAL_TIME` → `mealTime`, `SCAN_SUGGESTION`·`NEWS` → 항상 true(동의만). 광고성이면 회원 열린 동의 두 종류가 `MARKETING_CONSENT_REQUIRED_VERSION = 2` 이상.
- `NotificationType`: `NEWS(marketing = true)` — KB-468 에서 `NOTICE` 를 `NEWS` 로 교체했다. spec Assumptions 의 "정보성 NOTICE" 문구는 구 명칭이며, 광고성 = `SCAN_SUGGESTION`·`NEWS`·`MEAL_TIME` 이 코드 사실이다.
- 설정 API: `GET/PATCH /api/notifications/settings` 무버전 매핑(1.0 부터). PATCH 만 `X-Installation-Id` 선택 헤더를 받아 동의 행의 "동의 받은 기기" 로 남긴다. 검증은 `ApiHeaders.validInstallationId`(공백·36자 초과 → `COMMON-002`).
- 버전 마커 현황: `1.0`·`1.0+`·`1.1+`(토큰 등록·온보딩·프로필)·`2.0+`(`ScanV2Controller`). 가장 높은 마커는 `2.0`.
- 생명주기: `AuthService.login → linkOnLogin`, `logout → unlinkOnLogout`(기기 연결 해제만), `withdraw → closeOnWithdraw`(전 기기 연결 해제 + 열린 동의 닫기). 설정 행은 어느 경로도 건드리지 않는다.
- `PushNotificationService.send` 의 유일한 호출자는 `AdminNotificationTestService`(관리자 테스트 발송). 운영 스케줄 발송은 아직 없다.
- common 리포지토리 테스트는 Hibernate `create` 로 스키마를 만든다 — 엔티티의 `@Table(uniqueConstraints)` 가 곧 그 테스트의 제약이다. 마이그레이션 검증은 api 컨텍스트(Flyway on + `ddl-auto=validate`)가 한다.

## 결정 1 — 스키마: 같은 테이블 확장 + 고유 제약 교체

**Decision**: `notification_setting` 에 `installation_id VARCHAR(36) NULL`·`news BOOLEAN NOT NULL DEFAULT FALSE` 를 추가하고, `uk_notification_setting_member(member_id)` 를 **제거**한 뒤 `uk_notification_setting_member_installation(member_id, installation_id)` 를 추가한다. 마이그레이션 1건, ALTER 두 문장(ADD 먼저 → DROP INDEX). 기존 행(installation_id NULL)은 그대로 둔다.

**Rationale**: spec 의 Edge Case 두 줄이 서로 충돌한다 — "구 고유 제약(회원당 한 행)은 이번에 제거하지 않는다" 와 "이번 릴리스 동안 신 코드는 회원당 기기별 행을 여러 개 만들 수 있어야 한다". MySQL 에서 `UNIQUE(member_id)` 를 남긴 채 회원당 여러 행을 넣을 방법은 없다. FR-001(기기 단위 저장)이 기능의 존재 이유이므로 고유 제약 교체가 불가피하다. 구 코드가 신 스키마 위에서 도는 블루/그린 구간의 실제 위험은 아래로 한정된다:
- 구 코드는 새 컬럼을 모른다(`ddl-auto=validate` 는 여분 컬럼을 무시) → 부팅·조회 정상.
- 구 코드 `findByMemberId` 는 단건 반환이라, **같은 회원에 행이 2개 이상**이면 `IncorrectResultSizeDataAccessException`(500). 행이 2개 이상이 되려면 신 계약(2.1 헤더)으로 기기 행이 생겨야 하는데, FE 릴리스는 BE 배포 뒤이므로 카나리 구간에 그런 회원은 사실상 없다. 감수한다.
- FK `fk_notification_setting_member` 는 새 고유키의 선두 컬럼 `member_id` 를 인덱스로 쓰므로 DROP INDEX 가 막히지 않는다(ADD 를 먼저 실행).

**Alternatives considered**:
- 새 테이블 `notification_device_setting` — 구 제약을 손대지 않아 블루/그린이 가장 안전하지만, Jira 제목·위키 결정("notification_setting 을 (회원, 기기) 단위로 재정의")과 어긋나고 엔티티·리포지토리·테스트가 한 벌 더 생긴다. 구 계약 폐기 뒤 남는 정리도 "NULL 행 삭제" 가 아니라 "테이블 삭제 + 코드 이관" 이 된다. 기각.
- 구 제약 유지 — 불가능(위).

**후속(이번 범위 밖)**: 구 계약(무버전 매핑) 폐기 시 `installation_id IS NULL` 행 삭제 + 컬럼 `NOT NULL` 승격. 별도 Jira 태스크.

## 결정 2 — 새 계약 버전 마커: `2.1+`

**Decision**: 새 설정 조회·수정은 같은 경로에 `version = "2.1+"` 매핑을 추가한다. 무버전 매핑(구 계약)은 그대로 둔다. 1.0·1.1·2.0 요청은 무버전 매핑으로, 2.1 이상은 새 매핑으로 간다(버전 조건 매핑이 우선).

**Rationale**: 버전 번호는 앱 릴리스 마커이고 현재 최고 마커가 2.0 이므로 다음 릴리스는 2.1 이다. FE(KB-497)가 다른 번호를 쓰기로 하면 매핑 상수 두 곳(GET·PATCH)만 바꾸면 된다 — **FE 와 번호 확정은 구현 전 확인 항목**으로 남긴다(FR-014).

**Alternatives considered**: `3.0+`(계약 파괴 느낌을 주지만 실제로는 헤더 추가·응답 형태 동일이라 과함), 새 경로(`/settings/device` — 경로 규약 위반: 같은 계약의 새 버전은 경로를 바꾸지 않는다). 둘 다 기각.

## 결정 3 — 조회 응답은 동의 판정을 하지 않는다 (2026-09-11 개정)

**Decision**: 새 계약의 `news.enabled` 는 **이 기기 행의 `news` 저장값 그대로**, `news.mealTime` 은 `mealTime && news`(기기 저장값끼리), `privacyConsent`·`receiveConsent` 는 종류별 열린 최신 1건. 서비스는 "동의 유효(버전 ≥ 요구치)" 판정을 하지 않는다 — 그 판정은 발송(`PushTargetResolver`, 요구 버전 2)만 한다. 구 계약 경로는 종전대로(동의 결합·버전 무관).

**Rationale**: FE 검토(2026-09-11)로 US2 가 "소식 토글 ↔ 동의 분리" 로 바뀌었다. 화면이 「소식」 토글과 「마케팅 수신 동의」 토글을 따로 그리므로 응답도 두 값을 따로 준다 — 소식은 기기 저장값, 동의는 두 동의 항목의 유무. 개정 전 결정("enabled = 기기 토글 AND 동의 유효")은 두 토글을 다시 한 값으로 뭉개므로 폐기.

**Alternatives considered**: 응답에 `consent: Boolean` 계산 필드 추가 — FE 가 두 동의 항목 유무로 이미 읽을 수 있어 중복. 기각(요청 시 한 줄 추가 가능).

## 결정 4 — 구 계약 경로는 `installation_id IS NULL` 행만 본다

**Decision**: `findByMemberId` 를 `findByMemberIdAndInstallationIdIsNull(memberId): NotificationSetting?` 로 바꾸고 구 계약 서비스 코드는 이것만 쓴다. `defaultFor(memberId)` 는 유지(installationId null).

**Rationale**: FR-005·FR-013 — 구 계약은 기존 행을 계속 쓰고, 새 계약은 그 행을 읽지 않는다. 새 고유키 `(member_id, installation_id)` 는 MySQL 규칙상 NULL 을 서로 다른 값으로 보므로 구 계약의 첫 저장이 동시에 두 번 들어오면 NULL 행이 두 개 생길 수 있다 — 비치명 경합이라 감수한다(격리수준·락 추가 금지 규율).

## 결정 5 — 동의는 명시적 `consent` 항목으로만 켜고 끈다; 마지막 기기 규칙 없음 (2026-09-11 개정)

**Decision**: 새 계약 요청의 소식 그룹은 `enabled?`(이 기기 수신)·`consent?`(회원 동의)·`mealTime?`·두 버전으로 나뉜다. `consent: true` → `consentService.grantForMember(memberId, installationId, versions, now)`(두 버전 필수, 같은 버전 무변화·다른 버전 재개방 — 기존 로직 재사용). `consent: false` → `consentService.revokeForMember(memberId, now)`(회원 전체). `enabled` 는 `updateNews` 만. 처리 순서 `activity → consent → enabled → mealTime`. "회원의 연결 기기가 전부 꺼지면 동의를 닫는다" 규칙은 **없다** — `NotificationDeviceJpaRepository` 주입도, `existsBy...NewsTrue...In` 쿼리도 만들지 않는다.

**Rationale**: FE 검토 — 기기 한 대뿐인 대부분의 사용자에게 「소식」 끄기가 「동의」 철회로 번지면 두 토글이 거짓 상태가 된다. 동의는 법적 행위라 사용자의 명시적 조작(시트 체크 2·확인 모달)으로만 바뀌어야 한다. 코드도 줄어든다(쿼리 1·의존 1 감소).

**Alternatives considered**: 개정 전 마지막 기기 규칙 — 폐기. `enabled: true` 를 동의 없으면 거부 — FE 가 동의 없을 때 토글을 비활성으로 그리고 발송이 동의를 검사하므로 서버 거부는 중복이고, 동의 철회 뒤 남은 저장값과도 모순(spec Edge Case). 기각.

## 결정 5-1 — 새 계약 요청 DTO 는 별도 클래스

**Decision**: `DeviceNotificationSettingsUpdateRequest`(`activity?`, `news: DeviceNewsUpdateRequest?`) 와 `DeviceNewsUpdateRequest`(`enabled?`, `consent?`, `mealTime?`, `privacyConsentVersion?`, `receiveConsentVersion?` + `@AssertTrue`: `consent != true || 두 버전 존재`) 를 새 파일 `api/.../notification/DeviceNotificationSettingsUpdateRequest.kt` 에 둔다. 구 `NotificationSettingsUpdateRequest` 는 무변경.

**Rationale**: 구 계약은 `enabled: true` 에 버전 필수(400), 새 계약은 `enabled: true` 에 버전 불필요·`consent: true` 에 필수 — 한 DTO 의 `@AssertTrue` 로 두 규칙을 동시에 만족시킬 수 없다. 이름은 버전 번호가 아니라 계약 차이("Device")로 짓는다(`ProfileUpdateNoCountryRequest` 선례). 구 계약 폐기 시 구 파일만 지운다.

**Alternatives considered**: 단일 DTO + 서비스에서 구 경로만 버전 존재 검사 — 검증 소유 계층(요청 경계) 원칙 위반·구 테스트 메시지 의존 위험. 기각.

## 결정 6 — 발송 대상은 (회원, 기기) 키로 조회

**Decision**: `PushTargetResolver` 는 `settingRepository.findByMemberIdIn(memberIds)` 결과를 `(memberId, installationId)` 로 키잉하고 기기마다 자기 행을 찾는다(행 없음 = 전부 꺼짐). 토글 매핑: `HELPFUL`·`REVIEW_REMINDER` → `activity`, `MEAL_TIME` → `mealTime`, `SCAN_SUGGESTION`·`NEWS` → `news`. 광고성이면 회원 동의 버전 ≥ 요구치 조건은 그대로.

**Rationale**: FR-009·US3. 리포지토리 메서드 추가 없이 기존 `findByMemberIdIn` 을 재사용한다(installation_id NULL 행은 키가 안 맞아 자연히 무시).

**감수하는 결과(flag)**: 배포 직후엔 기기 행이 하나도 없어 **모든 회원이 모든 유형의 발송 대상에서 빠진다**(구 앱 사용자는 새 계약을 쓸 때까지 계속). spec FR-002·US3-3 이 명시한 동작이고, 현재 발송 호출자는 관리자 테스트 발송뿐이라 운영 영향은 없다. 스케줄 발송을 켜기 전에 FE 릴리스가 먼저 나가야 한다.

**Alternatives considered**: 기기 행이 없으면 회원 행으로 폴백 — 과도기엔 친절하지만 "설정 없는 기기는 꺼짐" 규칙을 깨고 폴백 제거 시점을 또 만든다. 기각.

## 결정 7 — 생명주기: 로그아웃 무변경, 탈퇴는 기기 행 소프트 삭제, 토큰 등록 무변경

**Decision**: `unlinkOnLogout` 은 손대지 않는다(설정 행 보존 = 아무것도 안 함). `closeOnWithdraw` 에 `settingRepository.findByMemberIdAndInstallationIdIsNotNull(memberId).forEach { it.delete() }` 를 더한다. `registerToken`·`linkOnLogin` 은 설정 행을 만들지 않는다(현행 유지).

**Rationale**: FR-010·FR-011. 소프트 삭제 뒤 같은 (회원, 기기) 재삽입은 고유키에 막히지만, 탈퇴 회원은 다시 활성화되지 않으므로 발생하지 않는다. 구 계약의 NULL 행은 탈퇴 시에도 종전처럼 건드리지 않는다(범위 밖).

## 결정 8 — 소식 켜기는 하위 토글을 건드리지 않는다; 응답 DTO 는 재사용 (2026-09-11 개정)

**Decision**: 응답 DTO(`NotificationSettingsResponse`)는 그대로 쓴다. 새 계약의 `enabled: true` 는 이 기기 `news` 만 켠다 — 구 계약의 "켜기가 하위 토글(mealTime)도 켠다" 캐스케이드는 새 계약에 없다. `mealTime: true` 는 (같은 요청 반영 후) 이 기기 `news == true` 여야 하고 아니면 `NOTIFICATION-001`; 동의 유무는 조건이 아니다.

**Rationale**: FE 매핑이 "소식 ON/OFF → enabled, 식사 시간 → mealTime" 1:1 이라 캐스케이드가 있으면 화면과 저장값이 어긋난다. `NOTIFICATION-001` 의 메시지("K-Bap 소식 수신 동의 후…")는 구 계약과 공유하는 표시 문구라 그대로 둔다 — 클라이언트는 코드로만 분기한다.

## 결정 9 — 서비스 배치: `NotificationService` 에 기기 메서드 추가, 새 클래스 없음

**Decision**: `NotificationService` 에 `getDeviceSettings(memberId, installationId)`·`updateDeviceSettings(memberId, installationId, request: DeviceNotificationSettingsUpdateRequest)` 를 추가한다. 구 메서드 `getSettings`·`updateSettings` 는 유지. 동의 항목 추출(종류별 열린 최신)은 private 함수로 나눠 두 조립이 공유한다. 컨트롤러는 `NotificationController` 에 2.1+ 매핑 두 개, Swagger 문서는 `NotificationApi` 에 메서드 두 개 추가.

**Rationale**: 기능 단위 단일 서비스 규율(CQRS·버전별 클래스 금지). 구 계약 삭제 시 메서드 두 개만 지우면 된다.

## 결정 10 — 문서: Swagger 서술 + 위키 절 + FE 공유

**Decision**: `NotificationApi` 새 메서드 서술에 헤더 필수·`enabled`(기기 저장값)·`consent` 켜기/끄기·mealTime 선행 조건·구 버전 동작을 적는다. 구현 마무리에 `../kbap-agenthub/wiki/push-notification-marketing-consent.md` 의 "KB-544 예고" 절을 "확정" 절로 갱신하고 INDEX 한 줄을 고친다(`update-agenthub`). FE 에는 버전 번호(2.1)와 헤더 필수를 KB-497 코멘트로 공유한다 — 사람이 하는 일이라 tasks 에 체크 항목으로만 둔다.

## 결정 11 — 새 버전 매핑 없이 기존 매핑에서 계약 교체 (2026-09-11 2차 개정, 사용자 결정)

**Decision**: 결정 2(`2.1+` 매핑)·결정 4(NULL 행 = 구 계약)·결정 5-1(요청 DTO 분리)·결정 1 의 "NULL 행 잔존" 을 **폐기**한다. `GET/PATCH /api/notifications/settings` 는 무버전 매핑(1.0 부터) 그대로 기기 단위 계약으로 바뀐다. 구 회원 단위 서비스 경로·DTO·리포지토리 메서드(`findByMemberIdAndInstallationIdIsNull`·`defaultFor(memberId)`)는 삭제. 마이그레이션은 기존 회원 단위 행을 `DELETE` 한 뒤 `installation_id` 를 `NOT NULL` 로 올린다. springdoc `2.1` 그룹도 제거.

**Rationale**: 사용자 지시 — "dev 환경이라 문제 없음, 1.0 유지하면서 수정". 이 기능은 아직 dev 에서만 쓰여 구버전 앱 호환·블루/그린 공존을 지킬 이유가 없고, 두 계약을 병존시키는 코드(서비스 메서드 2벌·DTO 2벌·NULL 행 분기)가 전부 사라져 더 단순하다.

**감수하는 것**: 헤더 없이 부르던 앱은 400. 블루/그린 구간에 구 코드가 설정을 저장하면 NOT NULL 위반(500) — dev 전용. 기존 회원 단위 설정값은 사라지고 앱이 기기별로 다시 저장한다.
