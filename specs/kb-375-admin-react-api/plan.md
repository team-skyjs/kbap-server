# Implementation Plan: 신 관리자(React)용 관리자 REST API

**Branch**: `kb-375-admin-react-api` | **Date**: 2026-08-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-375-admin-react-api/spec.md` · 운영자 인터뷰 [interview.md](interview.md) · 현행 인벤토리 [current-admin-api.md](current-admin-api.md)

## Summary

구 Thymeleaf 관리자 화면(`/admin/**`)을 그대로 살려둔 채, 화면 층이 들고 있던 데이터·조작을 `/api/admin/**` REST 로 연다. 핵심은 세 가지 — (1) **관리자 자격 분리**: JSON 로그인·갱신(갱신 토큰 `role` 클레임으로 회원 경로와 교차 거부)·5회 잠금·`@AuthAdminId`; (2) **검증·전이의 도메인 소유**: 콘텐츠 검증기 단일 출처(랭체인 적재·REST 수정·구 화면 공유), `Food.allowedTransitions()` + 전이 메서드(상태 드롭다운 백도어 제거), `version` 필수 낙관락; (3) **감사 이력** `admin_audit_log` 를 모든 관리자 쓰기에 명시 기록(구 화면 포함). 그 위에 목록 네이티브 프로젝션(재료/번역/id 검색·삭제 포함), 상세 이력 동봉, 파이프라인 개입(개별 재수집·READY 유지 이미지 재생성/업로드·배치 즉시 회수·고착 아웃박스 requeue/cancel·벡터 일괄 재시도), 회원 검색/상세/제재(정지 전용 오류 MEMBER-012)/프로필 초기화/scan-unlock/강제 탈퇴를 얹는다. 스키마 변경은 마이그레이션 3개(감사 로그 테이블, member 정지 컬럼 2, 콘텐츠 아웃박스 CANCELED+실패 컬럼 2) — 전부 NULL 허용·enum 값 추가라 리비전 공존 안전.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존)

**Primary Dependencies**: Spring Boot 4.1 · Spring Data JPA · jjwt(관리자 토큰 role 클레임) · Redis(`StringRedisTemplate` — 로그인 잠금 카운터, 갱신 토큰 기존 저장소) · ShedLock `LockingTaskExecutor`(수동 회수 상호배제) · springdoc(관리자 그룹)

**Storage**: MySQL — 신규 `admin_audit_log`, `member.suspended_at/suspend_reason`, `food_content_outbox.{last_error,last_failed_at}` + ENUM `CANCELED`. Flyway timestamp 마이그레이션 3개. Redis 키 `admin:login-fail:{loginId}`(TTL 15m)

**Testing**: Kotest BehaviorSpec — 컨트롤러 통합(`@SpringBootTest`+MockMvc+MySQL Testcontainers, 엔드포인트 묶음별 1파일) + 도메인/검증기 순수 단위. 관리자 토큰 공용 헬퍼 `AdminTestTokens`(신규, test 소스)

**Target Platform**: `:api` web bootJar. `:batch` 는 `FoodContentOutboxPublisher` 의 실패 사유 기록 시그니처 1곳만 변경

**Project Type**: 모듈러 모놀리스 — `:common`(엔티티·enum·리포지토리 쿼리·seam) + `:api`(`com.kbap.api.admin` 컨트롤러·서비스·검증기, `api.infra.redis` 잠금 어댑터, `core.auth` 필터/리졸버)

**Performance Goals**: 목록 1만 건 2초 이내(SC-011) — 네이티브 프로젝션 + 기존 인덱스(`idx_food_content_status`, `uq_food_korean_name`; 회원은 PK/`uk_member_provider_uid`). 상세 1회 ≤ 10 쿼리. JSON 검색(`JSON_SEARCH`)은 풀스캔 — 1만 건 규모에서 수백 ms, 관리자 트래픽 허용(후속: 생성 컬럼 인덱스)

**Constraints**: 구 화면 전 기능 회귀 없음(FR-043) · 기존 REST 7개 계약 하위 호환(FR-044 — 시드 응답은 필드 추가만) · `@SQLRestriction` 우회는 네이티브만 · 배치 발행 주체(`:batch`)는 건드리지 않되 실패 사유 기록만 확장 · 랭체인 콜백 계약(`POST /api/admin/foods/contents`, `content-reviews`) 무변경(검증기 추출은 동작 동일)

**Scale/Scope**: 신규 엔드포인트 약 40개, 컨트롤러 8개(`AdminAuth`·`AdminDashboard`·`AdminFoodQuery`·`AdminFoodCommand`·`AdminFoodPipeline`·`AdminMember`·`AdminAuditLog`·`AdminIngredient`), 리포지토리 신규 쿼리 약 20개, 마이그레이션 3, 에러 코드 9

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 스토리·엔드포인트 묶음별 Red→Green. 전이 규칙·검증기는 단위 테스트 선행, 구 화면 회귀는 기존 테스트 유지 |
| II. Bounded Contexts | PASS (허용 맵 확인 필요) | 관리자 조합 코드는 `com.kbap.api.admin`(도메인 맵 대상 아님). `common.domain` 간 새 참조: 회원 상세 count 쿼리는 각 소유 도메인 리포지토리에 두고 api 가 조립 — 도메인 간 참조 없음. `ReportJpaRepository.countReceivedByMemberId` 는 report→review 네이티브 join(문자열 SQL, 타입 참조 없음) → 맵 무변경. `AdminAuditLog` 는 `common.domain.admin` 소유, 타 도메인 타입 미참조(target_id Long) |
| III. Dependency Direction | PASS | `LoginAttemptStore` seam 은 `common.port.auth`, 구현 `api.infra.redis`, 조립 `core.config`(ADR-0018). 배치 변경은 `common` 리포지토리 시그니처를 통해서만 |
| IV. Persistence Ownership | PASS | 전이 규칙·`replaceImage`·`suspend`·`requeue/cancel` 은 엔티티 메서드. 네이티브 Custom 리포지토리는 소유 도메인 패키지. 창구 서비스 신설 없음 — 관리자 서비스는 조합·검증·감사 기록을 소유. 트랜잭션은 관리자 서비스 public 메서드가 명시 |
| V. Language Policy | PASS | 번역 9개 언어 검증은 기존 `LanguageCode` 집합 그대로. 상태 `label` 은 관리자 UI 문구(콘텐츠 번역 정책과 무관) |

**게이트 통과.** 재검토(Phase 1 설계 후): 변경 없음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-375-admin-react-api/
├── plan.md
├── research.md            # 결정 22개
├── data-model.md          # 테이블·엔티티·전이 표·마이그레이션
├── quickstart.md          # 검증 시나리오 24
├── contracts/
│   ├── admin-auth-audit.md   # 로그인/갱신/로그아웃·감사 조회·대시보드
│   ├── admin-foods.md        # 목록/상세/수정/검수/전이/삭제·복구/일괄/재수집/이미지/배치/아웃박스/시드/카탈로그
│   └── admin-members.md      # 회원 목록/상세/랭킹 원장/제재/프로필/scan-unlock/강제 탈퇴
├── interview.md · current-admin-api.md   # 입력 자료
└── tasks.md               # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── core/error/ErrorCode.kt                          # AUTH-009/010, MEMBER-012, FOOD-005~010
├── port/auth/
│   ├── LoginAttemptStore.kt                         # 신규 seam
│   └── ParsedRefreshToken.kt                        # role 추가(기본 USER)
├── domain/admin/
│   ├── model/AdminAuditLog.kt · AdminAuditAction.kt · AdminAuditTargetType.kt   # 신규
│   ├── AdminAuditLogJpaRepository.kt (+ Custom 네이티브 페이지)                 # 신규
├── domain/food/
│   ├── model/Food.kt                                # allowedTransitions·transition·replaceImage·approve 전제
│   ├── model/FoodTransition.kt                      # 신규 enum
│   ├── model/FoodContentStatus.kt                   # displayName
│   ├── model/FoodContentOutbox.kt · FoodContentOutboxStatus.kt   # requeue/cancel, CANCELED, lastError
│   ├── FoodJpaRepository.kt                         # findByIdIncludingDeleted·restore·findKoreanNamesIncludingDeleted(native)
│   ├── FoodAdminQueryRepository(+Impl).kt           # 신규 — 네이티브 동적 목록(AdminFoodRow)
│   ├── FoodContentOutboxJpaRepository.kt            # 페이지/고착 조회·recordPublishFailed(error)·findTop10ByFoodId
│   ├── FoodVectorOutboxJpaRepository.kt             # findByOutboxStatus(page)·retryAllFailed·findTop5ByFoodId
│   ├── ImageBatchJpaRepository.kt · ImageBatchItemJpaRepository.kt   # 페이지·배치 아이템·findTop10ByFoodId·findByIdIn
│   └── dto/AdminFoodRow.kt                          # 신규 프로젝션
├── domain/member/
│   ├── model/Member.kt                              # suspend/reinstate/unlockScan/resetNickname/resetProfileImage
│   ├── MemberJpaRepository.kt                       # findByProviderAndProviderUid(상태 무관)
│   ├── MemberAdminQueryRepository(+Impl).kt         # 신규 — 네이티브 동적 목록(AdminMemberRow)·findByIdIncludingWithdrawn
│   ├── MemberRankingEventJpaRepository.kt           # findByMemberIdOrderByIdDesc(page)
│   └── dto/AdminMemberRow.kt
├── domain/{review,order,scan,bookmark,block,report}/*JpaRepository.kt   # countByMemberId 류 + countByFoodId(scan·bookmark) + countReceivedByMemberId
├── domain/metering/LlmCallCostJpaRepository.kt · dto/DailyModelCostSum.kt   # costKrw 합계
└── domain/image/model/UploadPurpose.kt              # FOOD("food")

api/src/main/kotlin/com/kbap/api/
├── core/auth/
│   ├── JwtAuthenticationFilter.kt                   # ADMIN → authAdminId 속성·MDC adminId
│   ├── AuthAdminId.kt · AuthAdminIdArgumentResolver.kt   # 신규
├── core/config/WebConfig.kt · OpenApiConfig.kt · AuthConfig.kt · PropertiesConfig.kt   # 리졸버 등록·admin 그룹·admin TTL·잠금 어댑터 조립
├── infra/auth/token/JwtTokenIssuer.kt · JwtTokenParser.kt · JwtTokenProperties.kt   # refresh role 클레임·관리자 TTL
├── infra/redis/RedisLoginAttemptStore.kt            # 신규
├── auth/AuthService.kt                              # refresh: role=ADMIN 거부
├── member/MemberService.kt                          # findOrSignUp·getMember: SUSPENDED → MEMBER-012
├── food/FoodImageBatchSubmitService.kt              # submitForFoods(ids)
├── food/FoodImageBatchCollectService.kt             # replaceImage 사용·collect 결과 반환
└── admin/
    ├── AdminAuthController.kt · AdminAuthApi.kt · AdminAuthService.kt(login/refresh/logout + 잠금)   # AdminLoginService 확장
    ├── AdminAuditRecorder.kt · AdminAuditLogController.kt · AdminAuditLogApi.kt · AdminAuditLogService.kt
    ├── AdminDashboardController.kt · AdminDashboardApi.kt   # AdminFoodDashboardService·AdminDashboardMetricsService 확장(days·label·costKrw·stuck)
    ├── FoodContentValidator.kt · FieldError.kt      # 검증 단일 출처 (AdminFoodContentIngestRequest 가 위임)
    ├── AdminFoodController.kt · AdminFoodApi.kt · AdminFoodService.kt(확장) · AdminFoodDtos.kt   # 목록·상세(이력 동봉)·카탈로그·PUT·approve/reject/transitions·delete/restore — 읽기/쓰기 분리 없음
    ├── AdminFoodPipelineController.kt · AdminFoodPipelineApi.kt · AdminFoodPipelineService.kt   # 이미지 재생성/업로드/배치/회수/재제출·콘텐츠 아웃박스·벡터 아웃박스
    ├── AdminMemberController.kt · AdminMemberApi.kt · AdminMemberService.kt(구 AdminMemberQueryService 확장 — 조작 메서드 동거)
    ├── AdminFoodService.kt                          # updateFood(version, 검증기)·contentStatus 제거·seedIncomplete 확장  ← 구 화면 공유
    ├── AdminFoodPageController.kt                   # version 파라미터·approve/reject 폼·감사 기록
    └── AdminPageAuthInterceptor.kt                  # authAdminId 속성 공급
api/src/main/resources/
├── db/migration/V2026.08.25.*__{admin_audit_log_table,member_suspension_columns,food_content_outbox_cancel_and_error}.sql
├── templates/admin/food-list.html                   # version hidden·max=10·상태 읽기 전용·승인/반려 폼
└── application.yml                                  # kbap.auth.admin.{access-ttl,refresh-ttl}, kbap.admin.login-lock.{max-attempts,duration}

batch/src/main/kotlin/com/kbap/batch/outbox/FoodContentOutboxPublisher.kt   # recordPublishFailed(ids, error)

api/src/test/kotlin/com/kbap/api/admin/
├── AdminTestTokens.kt(공용 헬퍼) · AdminAuthControllerTest · AdminAuditLogControllerTest · AdminDashboardControllerTest
├── AdminFoodBrowseControllerTest · AdminFoodEditControllerTest · AdminFoodTransitionTest(단위) · FoodContentValidatorTest(단위)
├── AdminFoodPipelineControllerTest · AdminFoodImageReplaceTest · AdminContentOutboxControllerTest
├── AdminMemberControllerTest · AdminMemberSuspensionLoginTest
└── (기존 20개 유지 + AdminFoodPageControllerTest 에 version/approve 시나리오)
```

**Structure Decision**: 관리자 REST 는 `com.kbap.api.admin` 단일 기능 패키지에 두되 파일 수가 많아 **컨트롤러를 관심사별 8개**로 나눈다(하위 패키지는 만들지 않음 — 규약). 구 화면 컨트롤러(`Admin*PageController`)는 그대로 두고 **서비스만 공유**한다(`AdminFoodService.updateFood` 가 검증기·version 을 받도록 확장 → 구 화면과 REST 가 같은 규칙). 네이티브 동적 쿼리는 소유 도메인 패키지의 Custom 리포지토리 구현(`ReviewRepositoryCustomImpl` 선례)에 둔다.

## 핵심 설계 결정 (research.md 요약)

1. **갱신 토큰 `role` 클레임** — 회원/관리자 refresh 교차 거부. 관리자 TTL 은 프로퍼티(1h/7d), 포트 시그니처 불변.
2. **`@AuthAdminId` + `authAdminId` 속성 + MDC `adminId`** — 관리자·회원 식별자 분리. 구 화면 인터셉터도 같은 속성 공급.
3. **`LoginAttemptStore`(Redis INCR+TTL)** — 5회/15분 잠금.
4. **`admin_audit_log` 명시 기록**(`AdminAuditRecorder`) — AOP 없음, 변경 필드만 before/after.
5. **`FoodContentValidator` 단일 출처** — 적재·REST·구 화면 공유, `FOOD-006` + `errors[]`.
6. **`Food.allowedTransitions()`/`transition()`** — APPROVE(재료·이미지 전제)/REJECT/RESUBMIT/UNPUBLISH. PUT 은 상태 불수용.
7. **네이티브 프로젝션 목록** — `@SQLRestriction` 우회(삭제/탈퇴 포함)·JSON 검색·정렬/페이지.
8. **상세 이력 동봉** — 아웃박스·이미지 아이템·벡터·리뷰 요약·스캔/북마크 수·감사 10건.
9. **`Food.replaceImage` + `submitForFoods`** — READY 유지 재생성; 업로드는 기존 presigned 2단계 + `head` 검증.
10. **수동 회수는 ShedLock 락 공유** — 겹치면 409.
11. **콘텐츠 아웃박스 CANCELED·last_error** — 고착 = SENT ∧ sent_at 경과. 취소 건의 랭체인 콜백은 기존 `completeIfProcessable` 이 거절.
12. **정지 회원 `MEMBER-012`** — `findOrSignUp` 상태 무관 조회 후 판정, `getMember` 도 동일(필터 DB 조회 없음).
13. **강제 탈퇴 실패는 감사 이력**으로 기록·조회(별도 테이블 없음).

## Blast Radius

- **랭체인 콜백**: `AdminFoodContentIngestRequest` 의 `@AssertTrue` 검증을 `FoodContentValidator` 위임으로 바꾸되 메시지·규칙 동일 — 기존 `AdminFoodContentIngestValidationTest` 가 회귀 가드. `approve()` 전제 강화는 `content-reviews` 승인 경로에도 적용되나 PENDING_REVIEW 는 이미 이미지 있음을 전제(`applyContent`)라 실동작 무변경.
- **회원 로그인**: `findOrSignUp` 조회 조건 변경(ACTIVE → 상태 무관 + SUSPENDED 판정). 기존 `AuthService`·`MemberService` 테스트에 정지 케이스 추가. `getMember` 의 SUSPENDED 거부는 회원 API 전반에 영향 — 정지 회원이 없는 기존 테스트는 무영향.
- **갱신 토큰 파싱**: `ParsedRefreshToken` 에 `role` 추가(기본 USER) — 구 토큰 호환. `JwtTokenParserTest` 보강.
- **JWT 필터 속성**: ADMIN 토큰이 `authMemberId` 를 더 이상 심지 않음 — `AuthMemberIdArgumentResolver` 는 이미 ADMIN 을 거부하므로 동작 동일. `AdminAuthorizationInterceptor` 는 role 속성만 봄 → 무변경.
- **구 화면**: `food-list.html` 편집 폼(version hidden·max=10·상태 select 읽기 전용·승인/반려 폼), `AdminFoodPageController.updateFood` 시그니처(version 추가, contentStatus 무시). 기존 `AdminFoodListControllerTest`·`AdminFoodServiceTest` 의 상태 변경 시나리오는 전이 API 로 이관.
- **배치**: `FoodContentOutboxPublisher.publishAll` 의 실패 기록 호출에 예외 메시지 전달(리포지토리 시그니처 확장). 배치 테스트 1곳 갱신.
- **시드 REST 응답**: 필드 추가만(`createdIds`·`skippedNames`·`blockedByDeletedNames`) — `AdminControllerTest` 는 기존 3필드 검증 유지.
- **springdoc**: `admin` 그룹 추가 — 버전 그룹 문서 무영향.

## 구현 순서 제안 (tasks 입력)

Phase 0(자격·감사) → Phase 1-A(검증기·전이·PUT·approve/reject — 사고 위험 1·2·3) → Phase 1-B(목록·상세·대시보드·카탈로그) → Phase 1-C(재수집·이미지·배치·아웃박스·벡터·삭제/복구·일괄·시드) → 구 화면 최소 수정(Phase 1-A 와 함께) → Phase 2(회원). 각 Phase 는 독립 배포 가능하며 PR 은 Phase 단위로 나눌 수 있다(Phase 0 은 모든 쓰기 조작의 감사 기록 전제라 최우선).

## Complexity Tracking

위반 없음.
