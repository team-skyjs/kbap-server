# Research: 푸시 알림 데이터 기반

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 갈림길에서 내린 결정과 근거다.

## R1. 토큰 기록의 키 — installation_id 를 PK 로 쓸지

- **Decision**: `BaseEntity` 의 `id`(IDENTITY) 를 PK 로 두고 `installation_id` 는 **UNIQUE 컬럼**으로 둔다.
- **Rationale**: 프로젝트 규약상 모든 엔티티가 `BaseEntity`(id·status·시각) 를 상속하고 자체 id 를 두지 않는다. 자연키 PK 는 이 규약과 `@SQLRestriction` 소프트삭제 모델을 깨뜨린다. 유니크 제약으로 "기기당 1건" 은 동일하게 강제된다.
- **Alternatives considered**: `installation_id` PK(Jira 초안) — BaseEntity 규약 위반. 복합키 — 불필요.

## R2. upsert 의 동시성

- **Decision**: 리포지토리는 `findByInstallationId` 만 제공하고 upsert 판단은 후속 API(KB-465)가 한다. 동시 최초 등록은 유니크 제약 위반으로 한쪽이 실패하며, 이 경합은 감수한다.
- **Rationale**: 동시성 방어 수위 규약(2026-07-30) — 치명 정합만 최소 수단(유니크 제약)으로 막고 비치명 경합은 감수한다. 같은 기기의 동시 최초 등록은 재시도로 자연 해소된다.
- **Alternatives considered**: `INSERT ... ON DUPLICATE KEY UPDATE` 네이티브 쿼리 — 소프트삭제된 행(status=DELETED)과 충돌 시 의미가 모호해져 기각.

## R3. 기기 언어 저장 형식

- **Decision**: `lang VARCHAR(10)` 문자열 그대로 저장. `LanguageCode` enum 으로 변환하지 않는다.
- **Rationale**: 헌법 V — lang 은 기기에서 흘러드는 값이라 저장 시 거절하지 않는다. 미지원 코드의 `en` 폴백은 발송 렌더 단계(KB-468) 책임. 지원 코드 최장 길이는 `zh-Hans`(7).
- **Alternatives considered**: enum 컬럼 — 미지원 코드 저장 불가로 토큰 등록 자체가 실패한다.

## R4. 게스트 설정과 회원 설정의 표현

- **Decision**: 값 객체 `NotificationPreferences(helpful, reviewReminder, marketing, marketingConsentVersion)` 하나를 둔다. 회원 설정 테이블은 이를 **컬럼 4개 + marketing_opt_in_at** 으로 펼치고, 게스트 설정은 토큰 행의 `guest_settings JSON` 에 같은 값 객체를 직렬화한다.
- **Rationale**: 발송 대상 필터가 회원·게스트를 같은 타입으로 다룰 수 있다. 회원 설정은 쿼리 필터 대상이라 컬럼으로, 게스트 설정은 필터 빈도가 낮고 스키마 변경 없이 확장돼야 하므로 JSON 으로.
- **Alternatives considered**: 게스트 설정도 별도 테이블 — 게스트 식별자가 토큰 행뿐이라 조인만 늘어난다.

## R5. 광고성 수신 동의의 표현 (2026-09-07 FE 협의 결정)

- **Decision**: 동의 스위치 이름은 알림 유형(NUDGE)이 아니라 법적 카테고리 **`marketing`** 으로 둔다. 컬럼 `marketing`·`marketing_opt_in_at`·`marketing_consent_version`, API 계약 필드 `marketing`·`marketingOptInAt`·`marketingConsentVersion`. FE 내부 토글명 nudge 는 어댑터에서 매핑(FE 변경 없음). 알림 유형 `NUDGE` 는 그대로.
- **Decision**: `NotificationSetting.updateMarketing(enabled, consentVersion, now)` 가 off→on 전환 시 동의 시각(서버)과 **동의 문구 버전**(FE 가 보내는 코드 상수, 예: "v2")을 함께 기록한다. 발송 조건 = marketing on AND opt_in_at not null AND consent_version 이 발송에 필요한 버전 이상. 구 문구("점심 스캔 알림" 한정)로 켠 사용자는 v1 로 간주해 프로모션 대상에서 제외한다.
- **Decision**: 야간(21~08시) 전송 별도 동의는 **보류** — 1차는 점심 넛지만이라 컬럼·토글을 두지 않는다. 대신 발송 배치(KB-471)가 08~21 KST 밖에서는 보내지 않는 하드 가드를 둔다. 저녁 넛지가 기획되면 `marketing_night_opt_in_at` + FE 토글을 같이 추가.
- **Rationale**: 정보통신망법 50조 — 광고성 정보는 사전 동의·동의 증빙·야간 별도 동의가 필요하다. 현재 FE 문구는 카테고리 동의가 아니라 점심 스캔 알림 한정 동의라, 문구를 카테고리 동의로 바꾸는 FE 작업과 버전 컬럼이 같이 가야 구 동의자를 구분할 수 있다. 동의 시각은 서버 시각이어야 하며 엔티티가 상태 전이를 소유한다.
- **Alternatives considered**: 유형별 동의 컬럼(nudge·promo…) — 광고성 유형이 늘 때마다 동의를 다시 받아야 해 기각. 동의 문구 원문 저장 — 버전 코드로 충분.

## R6. 알림 수신자 식별과 FK

- **Decision**: `notification.member_id`(nullable, FK→member)·`notification.installation_id`(nullable VARCHAR(36), **FK 없음**) 두 컬럼. 둘 중 하나 이상은 애플리케이션이 보장한다(DB CHECK 미사용).
- **Rationale**: 게스트 알림함 제공 여부가 미결(KB-467)이라 스키마는 양쪽을 허용한다. 토큰 행이 삭제돼도 알림 이력은 남아야 하므로 토큰 FK 를 걸지 않는다.
- **Alternatives considered**: CHECK 제약 — MySQL 8 은 지원하지만 Hibernate validate 대상이 아니고 게스트 결정에 따라 바뀔 수 있어 보류.

## R7. 발송 추적의 토큰 참조

- **Decision**: `notification_dispatch.expo_token VARCHAR(255)` 스냅샷 + `notification_device_id BIGINT NULL`(FK 없음). `notification_id` 만 FK.
- **Rationale**: 영수증 정리(KB-473)가 `DeviceNotRegistered` 를 받으면 토큰을 소프트삭제하는데, 추적 기록은 "어느 토큰으로 나갔나" 를 계속 보여줘야 한다. 토큰 문자열이 곧 Expo 의 식별자라 스냅샷이 정확하다.

## R8. 알림 유형과 발송 상태의 컬럼 타입

- **Decision**: `notification.type` 은 **VARCHAR(30)**(Kotlin enum `NotificationType`, `@Enumerated(STRING)`), `notification_dispatch.dispatch_status` 는 **ENUM('PENDING','SENT','DELIVERED','FAILED')**, `notification_device.platform` 은 **ENUM('IOS','ANDROID')**.
- **Rationale**: 알림 유형은 spec 가정대로 마이그레이션 없이 추가돼야 하므로 문자열. 발송 상태·플랫폼은 닫힌 집합이라 기존 컬럼 규약(ENUM columnDefinition)을 따른다.

## R9. 알림 `data` 페이로드 타입

- **Decision**: `data JSON NULL`, 엔티티는 `Map<String, Any>`(`@JdbcTypeCode(SqlTypes.JSON)`). FE 계약 `{ type, foodId?: string, notificationId?: number }` 를 그대로 담는다.
- **Rationale**: 값 타입이 문자열·숫자 혼재라 `Map<String, String>` 으로는 계약을 못 지킨다. Hibernate JSON 매핑은 Jackson 으로 `Map<String, Any>` 를 그대로 직렬화한다.

## R10. 게스트 주문 리마인더

- **Decision**: 이 기능 범위 밖. `orders.member_id` 가 NOT NULL 이라 게스트 주문 자체가 저장되지 않는다.
- **Rationale**: 결정(KB-469)이 "보낸다" 로 나면 orders 스키마 변경이 별도로 필요하며, 이 기능의 테이블은 그 결정과 무관하게 유효하다.
