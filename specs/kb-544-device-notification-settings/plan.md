# Implementation Plan: 알림 설정 기기별 분리 — (회원, 기기) 단위 토글, 광고성 동의는 회원 단위 유지

**Branch**: `kb-544-device-notification-settings` | **Date**: 2026-09-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-544-device-notification-settings/spec.md`

## Summary

`notification_setting` 을 (회원, 기기) 단위로 재정의한다 — `installation_id`(NULL 허용)·`news` 컬럼을 더하고 고유키를 `(member_id)` 에서 `(member_id, installation_id)` 로 교체하는 마이그레이션 1건. 같은 경로 `GET/PATCH /api/notifications/settings` 에 `X-API-Version: 2.1+` 매핑을 추가해 `X-Installation-Id` 를 필수로 받고 이 기기의 행을 읽고 쓴다. 구 무버전 매핑은 `installation_id IS NULL` 행으로 종전 동작을 유지한다. 광고성 동의 원장(`notification_consent`)은 회원 단위 그대로이며, **새 계약의 소식 그룹은 기기 수신(`enabled`)과 회원 동의(`consent`)를 별개 항목으로 받는다**(2026-09-11 FE 검토 반영 — 당근식 두 토글). `consent: true/false` 만 원장을 열고 닫고, `enabled` 는 이 기기 `news` 만 바꾼다. 응답 `news.enabled` 는 기기 저장값, 동의 상태는 두 동의 항목의 유무다. `PushTargetResolver` 는 (회원, 기기) 키로 토글을 판정하고(행 없음 = 제외), `SCAN_SUGGESTION`·`NEWS` 는 새 `news` 토글 AND 회원 동의 유효를 본다. 탈퇴는 기기 행을 소프트 삭제하고 로그아웃·토큰 등록은 설정 행을 건드리지 않는다.

새 클래스는 요청 DTO 한 벌(`DeviceNotificationSettingsUpdateRequest`·`DeviceNewsUpdateRequest`, 파일 1)뿐이다. 변경은 엔티티 1·리포지토리 1·마이그레이션 1·서비스 3(`NotificationService`·`NotificationTokenService`·`PushTargetResolver`)·컨트롤러/문서 2·테스트 4 이다. 설계 결정은 [research.md](research.md)(결정 3·5·8 은 개정판), 스키마·쿼리는 [data-model.md](data-model.md), HTTP 계약은 [contracts/notification-settings.md](contracts/notification-settings.md).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(web·validation·data-jpa), Spring MVC 네이티브 API 버저닝(`X-API-Version` 헤더, 기존 `WebConfig`), springdoc-openapi, Flyway(+mysql)

**Storage**: MySQL — `notification_setting` 확장 마이그레이션 1건(컬럼 2 추가·고유키 교체). `notification_consent`·`notification_device` 무변경

**Testing**: Kotest BehaviorSpec + JUnit 5. api 는 `@IntegrationTest`(MockMvc + MySQL·Redis Testcontainers, Flyway on + `ddl-auto=validate`), common 은 `@SpringBootTest` + `MySqlContainerConfig`(Hibernate create — 엔티티 `@Table(uniqueConstraints)` 가 곧 제약)

**Target Platform**: Linux 서버(api bootJar). batch 는 알림 코드를 소비하지 않아 무영향

**Project Type**: web-service(Gradle 멀티모듈 모듈러 모놀리스 — `:common`·`:api` 만 변경)

**Performance Goals**: N/A — 설정 API 쿼리 수는 구 계약과 같다(설정 1·동의 1). 발송 대상 조회는 종전과 같은 3 쿼리(기기·설정·동의), 키만 바뀜

**Constraints**: 블루/그린 배포 중 구 코드가 신 스키마 위에서 돈다(SC-005) — 새 컬럼 NULL/DEFAULT·구 행 무변경. 구 계약 테스트 무변경 통과(SC-004). 격리수준·락 추가 금지(비치명 경합 감수). 부가 방어 기능 추가 금지. Kotlin 소스 주석 금지. 새 계약 버전 마커 `2.1` 은 FE(KB-497) 확인 항목(spec Assumptions)

**Scale/Scope**: 변경 파일 약 15개(main 10·test 4·마이그레이션 1), 신규 파일 2(마이그레이션·새 계약 요청 DTO), 신규 클래스 2(요청 DTO 한 벌)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 태스크마다 실패 테스트 먼저: (a) common `NotificationSettingJpaRepositoryTest` — (회원, 기기) 두 행 저장 성공·같은 쌍 중복 실패·NULL 행 조회, (b) `PushTargetResolverTest` — 기기 A 만 켜짐·행 없는 기기 제외·news 토글, (c) api `NotificationSettingControllerTest` — 2.1 헤더 시나리오 US1·US2 전부, (d) `AuthNotificationLinkTest` — 로그아웃 보존·탈퇴 소프트 삭제. 마이그레이션은 (c) 의 `ddl-auto=validate` 가 Red(컬럼 없음)→Green 을 드러낸다 |
| II. Bounded Contexts | PASS | 변경은 `common.domain.notification` 과 `api.notification`·`api.auth` 안. 새 주입 없음. `ModuleBoundaryTest` 허용 맵 무수정 |
| III. Layered Dependency Direction | PASS | api → common 방향 유지. 새 seam·어댑터 없음. 발송 규칙 변경은 common 의 `PushTargetResolver` 안에서 끝난다 |
| IV. Persistence Ownership | PASS | 엔티티에 도메인 메서드(`updateNews`·`defaultFor(memberId, installationId)`) 추가, 리포지토리 public, JPA 연관관계 없음(기기는 `installation_id` 값 참조). 트랜잭션 경계는 기존 `@Transactional` 메서드 안. 스키마는 Flyway(api owner). 동의 켜기/끄기·기기 수신·식사시간 선행 조건 정책은 `NotificationService`(api 기능 패키지 도메인 서비스)가 소유하고, 동의 원장 조작은 기존 `NotificationConsentService` 재사용 |
| V. Domain Content Language Policy | N/A | 음식 콘텐츠·`lang` 폴백과 무관. 요청 검증(두 버전 필수)은 요청 경계 DTO 의 `@AssertTrue` 가 소유 |

**추가 제약 검토**: 외부 호출 없음(발송 파이프라인의 Expo 호출 경로는 손대지 않음). 도메인 모델을 응답으로 노출하지 않음(기존 `NotificationSettingsResponse` 재사용). 응답 봉투 `BaseResponse`·경로 `ApiPaths.API` 유지. 버저닝은 같은 경로에 `version = "2.1+"` 매핑(버전별 클래스·경로 금지 규약 준수). `WebConfig` 보호 경로 `/api/notifications/*` 에 이미 포함 — 추가 등록 불필요.

**Gate 결과: PASS (Phase 0 진입).**

## Project Structure

### Documentation (this feature)

```text
specs/kb-544-device-notification-settings/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정(스키마·버전·응답 규칙·consent/enabled 분리·요청 DTO·발송·생명주기)
├── data-model.md        # Phase 1 — NotificationSetting 변경·마이그레이션 SQL·리포지토리 메서드·계산 규칙
├── quickstart.md        # Phase 1 — 검증 절차·curl·완료 체크
├── contracts/
│   └── notification-settings.md   # GET/PATCH /api/notifications/settings 2.1+ 계약(구 계약 대비표·시나리오 매핑)
└── tasks.md             # Phase 2 — /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V<생성시각>__notification_setting_per_device.sql   # installation_id·news 추가, 고유키 교체

common/src/main/kotlin/com/kbap/common/domain/notification/
├── model/NotificationSetting.kt                  # installationId?·news 필드, updateNews, defaultFor(memberId, installationId), uk 교체
├── NotificationSettingJpaRepository.kt           # findByMemberIdAndInstallationIdIsNull / ...AndInstallationId / ...IsNotNull
└── PushTargetResolver.kt                         # (memberId, installationId) 키잉, SCAN_SUGGESTION·NEWS → news
common/src/test/kotlin/com/kbap/common/domain/notification/
├── NotificationSettingJpaRepositoryTest.kt       # 기기별 두 행·같은 쌍 중복·NULL 행 조회
└── PushTargetResolverTest.kt                     # setting() 헬퍼에 installationId, 기기별 시나리오

api/src/main/kotlin/com/kbap/api/notification/
├── DeviceNotificationSettingsUpdateRequest.kt    # 신규 — 새 계약 요청 DTO(activity?, news{enabled?, consent?, mealTime?, 두 버전}) + @AssertTrue(consent→버전)
├── NotificationService.kt                        # getDeviceSettings·updateDeviceSettings(consent→enabled→mealTime), 구 메서드는 IsNull 조회로
├── NotificationTokenService.kt                   # closeOnWithdraw 에 기기 설정 행 소프트 삭제(settingRepository 주입)
├── NotificationController.kt                     # GET/PATCH "/settings" version = "2.1+" + X-Installation-Id 필수
└── NotificationApi.kt                            # getDeviceSettings·updateDeviceSettings Swagger 서술, 구 메서드에 대체 안내
api/src/test/kotlin/com/kbap/api/
├── notification/NotificationSettingControllerTest.kt   # given("기기별 설정 조회")·("기기별 소식 동의") 등 2.1 블록 추가, 기존 블록 무변경
└── auth/AuthNotificationLinkTest.kt              # 로그아웃 후 설정 보존, 탈퇴 시 기기 행 DELETED

../kbap-agenthub/wiki/push-notification-marketing-consent.md   # "KB-544 예고" → 확정 절(구현 마무리 시 update-agenthub)
```

**Structure Decision**: 기존 기능 패키지 `com.kbap.api.notification` 과 도메인 패키지 `com.kbap.common.domain.notification` 안에서 확장만 한다. 새 패키지 없음. 신규 파일은 마이그레이션 1 + 새 계약 요청 DTO 1(구 계약과 검증 규칙이 달라 분리 — research 결정 5-1).

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.

## Spec 과 다른 점 (의식적 결정 — 리뷰에서 확인)

| 항목 | spec | plan | 이유 |
|------|------|------|------|
| 구 고유키 `uk_notification_setting_member(member_id)` | 이번에 제거하지 않음 | **제거하고 `(member_id, installation_id)` 로 교체** | 남기면 회원당 기기별 여러 행이 불가능(FR-001 과 양립 불가). 블루/그린 위험은 research 결정 1 에서 한정·감수 |
| 새 버전 마커 | plan 에서 FE 와 확정 | `2.1+` 로 가정 | 최고 마커 2.0 의 다음. FE 확인 후 상수 두 곳만 조정 |
| 광고성 유형 | "정보성 NOTICE" 언급 | 코드 사실 `NEWS(marketing=true)` | KB-468 이 NOTICE→NEWS 로 바꿈 |

## Post-Design Constitution Check

Phase 1 산출물(data-model·contracts) 기준 재검토(2026-09-11 개정 반영): 엔티티 확장은 값 필드·도메인 메서드 추가뿐이고 연관관계 없음(원칙 IV), 스키마는 Flyway 1건으로 api 가 소유, 계약은 같은 경로에 버전 매핑 추가 + 새 계약 전용 요청 DTO(응답 DTO 재사용)로 봉투·경로·버저닝 규약 유지, 정책(동의 켜기/끄기·기기 수신·선행 조건)은 도메인 서비스 소유, 요청 검증은 요청 경계 소유(원칙 V 조항), 테스트는 BehaviorSpec 으로 시나리오별 Red 선행(원칙 I). **PASS.**

## 산출물 밖 후속 (플랜 범위 밖, 기록용)

- 구 계약(무버전 매핑) 폐기 시: `installation_id IS NULL` 행 삭제 + `installation_id NOT NULL` 승격 + 구 서비스 메서드 삭제. 별도 Jira 태스크로 등록해 KB-544 코멘트에 연결.
- 스케줄 발송(광고성·활동)을 켜기 전에 FE 2.1 릴리스가 먼저 나가야 한다 — 기기 행이 없는 회원은 전 유형 발송 대상에서 빠진다(research 결정 6).
- FE(KB-497)에 "2.1 + `X-Installation-Id` 필수 + 값은 기기별" 공유.
