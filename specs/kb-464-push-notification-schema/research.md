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

## R4. 광고성 동의의 저장 — 상태 컬럼이 아니라 원장 (2026-09-07 DBA·CTO 교차 검토 결론)

- **Decision**: 광고성 수신 동의는 `notification_setting`·`notification_device` 의 컬럼이 아니라 **`notification_consent` 원장 한 테이블**에 둔다. 행 1개 = 동의 1회의 생애(`granted_at`·`revoked_at`), `member_id NULL`(게스트) / `installation_id NULL`(동의 받은 기기) 에 `CHECK (둘 중 하나 이상)`. 회원·게스트가 한 구조, 정본 하나.
- **Rationale**: 초안(두 테이블에 같은 컬럼 3개)은 정본이 둘이라 "member_id 가 있으면 기기 컬럼 무시" 규칙에 의존했고, 그 규칙 아래서 **게스트 동의 → 로그인 승계 → 회원 철회 → 로그아웃** 경로에서 기기의 옛 동의가 되살아나 철회자에게 발송되는 구멍이 있었다. 또 상태 컬럼은 철회 시 시각·버전을 지워 **증빙이 사라졌다** — 정보통신망법 시행령의 2년 재확인·철회 처리 증빙이 불가능했다. 원장은 철회를 `revoked_at` 스탬프로 남기고 재동의·재확인을 새 행으로 두어 최초 동의 시각을 보존한다. "현재 동의" 는 `revoked_at IS NULL` 행이며 InnoDB 는 NULL 도 인덱싱하므로 상태 컬럼과 조회 비용이 같다.
- **승계 규칙(KB-465)**: 기기를 회원에 연결할 때 회원에게 열린 행이 없으면 그 기기의 열린 게스트 행에 `member_id` 를 채워 인수(`claim`, 시각 보존), 있으면 게스트 행을 `revoke`. 게스트 조회는 `member_id IS NULL` 조건을 반드시 건다.
- **동시성**: "주체당 열린 행 1개" 는 MySQL 부분 유니크가 없어 DB 강제 불가. 철회가 열린 행을 전부 닫고(`closeOpenByMemberId`) 발송 조회가 `DISTINCT` 라 위반해도 무해 — 동시성 규약(2026-07-30) 범위.
- **Alternatives considered**: 두 테이블 상태 컬럼(초안) — 위 결함. 기기 테이블에서만 제거(게스트 동의 미저장) — 게스트 스캔 제안 포기 + 철회 증빙 소실은 그대로. 회원/게스트 테이블 분리 — NULL 은 없어지지만 승계가 "닫고 새로 만들기" 두 문장이 되고 한 사람의 이력이 두 곳에 갈림. `member_id` 는 NULL 허용이어도 FK 가 걸려(값이 있으면 실존 회원 강제) 무결성을 잃지 않는다. 상태 컬럼 + 이력 테이블 이중 쓰기 — 정본 둘 문제의 재현. 파생 캐시 컬럼 — 측정 없이 이중 쓰기 회귀라 기각.

## R5. 광고성 동의 계약과 보류 사항 (2026-09-07 FE 협의)

- **Decision**: 동의 스위치 이름은 알림 유형(SCAN_SUGGESTION)이 아니라 법적 카테고리 **`marketing`**. API 계약 `marketing`·`marketingOptInAt`·`marketingConsentVersion`(**정수**, 구 문구 = 1). FE 내부 토글명 nudge 는 어댑터 매핑. 알림 유형 `SCAN_SUGGESTION` 는 그대로.
- **Decision**: 동의 문구 버전은 `SMALLINT UNSIGNED` 정수. 문자열 비교는 `"v10" < "v2"` 함정이 있어 금지.
- **Decision**: 야간(21~08시) 전송 별도 동의는 **보류** — 발송 배치(KB-471)가 08~21 KST 밖에서는 보내지 않는 하드 가드. 저녁 스캔 제안가 기획되면 원장에 `kind` 컬럼 추가.
- **Rationale**: 정보통신망법 50조 — 광고성 정보는 사전 동의·동의 증빙·야간 별도 동의가 필요. 현재 FE 문구는 카테고리 동의가 아니라 점심 스캔 제안 한정 동의라, 문구를 바꾸는 FE 작업과 버전이 같이 가야 구 동의자를 구분할 수 있다.

## R6. 알림 수신자 식별과 FK

- **Decision**: `notification.member_id`(nullable, FK→member)·`notification.installation_id`(nullable VARCHAR(36), **FK 없음**) 두 컬럼. 둘 중 하나 이상은 애플리케이션이 보장한다(DB CHECK 미사용).
- **Rationale**: 게스트 알림함 제공 여부가 미결(KB-467)이라 스키마는 양쪽을 허용한다. 토큰 행이 삭제돼도 알림 이력은 남아야 하므로 토큰 FK 를 걸지 않는다.
- **Alternatives considered**: CHECK 제약 — MySQL 8 은 지원하지만 Hibernate validate 대상이 아니고 게스트 결정에 따라 바뀔 수 있어 보류.

## R7. 발송 추적의 토큰 참조와 무효 토큰 처리

- **Decision**: `notification_dispatch.expo_token VARCHAR(255)` 스냅샷 + `notification_device_id BIGINT NULL`(FK 없음). `notification_id` 만 FK. 영수증 정리(KB-473)가 `DeviceNotRegistered` 를 받으면 **기기 행을 삭제하지 않고 `notification_device.token_invalid_at` 을 스탬프**한다. 재등록(`renew`)이 스탬프를 지워 되살린다.
- **Rationale**: 기기 행을 소프트삭제하면 `installation_id` UNIQUE 는 남는데 `@SQLRestriction` 조회는 그 행을 못 봐 같은 기기의 재등록이 영구히 duplicate key 로 실패한다(DBA 검토 블로커). 기기 행은 설치 UUID 의 정체성이라 절대 삭제하지 않는다 — `NotificationDevice` 에는 `delete()` 를 호출하지 않는다. 추적 기록은 "어느 토큰으로 나갔나" 를 계속 보여줘야 하므로 스냅샷을 두고, 영수증 정리는 `notification_device_id` 로 기기를 찾는다(토큰 문자열 역조회·인덱스 불필요).

## R8. 알림 유형과 발송 상태의 컬럼 타입

- **Decision**: `notification.type` 은 **VARCHAR(30)**(Kotlin enum `NotificationType`, `@Enumerated(STRING)`), `notification_dispatch.dispatch_status` 는 **ENUM('PENDING','SENT','DELIVERED','FAILED')**, `notification_device.platform` 은 **ENUM('IOS','ANDROID')**.
- **Rationale**: 알림 유형은 spec 가정대로 마이그레이션 없이 추가돼야 하므로 문자열. 발송 상태·플랫폼은 닫힌 집합이라 기존 컬럼 규약(ENUM columnDefinition)을 따른다.

## R9. 알림 `data` 페이로드 타입

- **Decision**: `data JSON NULL`, 엔티티는 `Map<String, Any>`(`@JdbcTypeCode(SqlTypes.JSON)`). FE 계약 `{ type, foodId?: string, notificationId?: number }` 를 그대로 담는다.
- **Rationale**: 값 타입이 문자열·숫자 혼재라 `Map<String, String>` 으로는 계약을 못 지킨다. Hibernate JSON 매핑은 Jackson 으로 `Map<String, Any>` 를 그대로 직렬화한다.

## R10. 게스트 주문 리마인더

- **Decision**: 이 기능 범위 밖. `orders.member_id` 가 NOT NULL 이라 게스트 주문 자체가 저장되지 않는다.
- **Rationale**: 결정(KB-469)이 "보낸다" 로 나면 orders 스키마 변경이 별도로 필요하며, 이 기능의 테이블은 그 결정과 무관하게 유효하다.
