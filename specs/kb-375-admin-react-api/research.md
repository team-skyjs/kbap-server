# Research: KB-375 신 관리자(React)용 관리자 REST API

코드 사실은 2026-08-25 develop `cc8a7bf2` 기준(팩트 수집 결과는 plan 작성 시점에 검증). 각 결정은 spec 의 FR 번호를 참조한다.

## Phase 0 — 관리자 자격·감사

### Decision 1 — 관리자 로그인/갱신/로그아웃 REST 와 토큰 구분

- **Decision**: `POST /api/admin/auth/login {id, password}` → `{accessToken, refreshToken}`, `POST /api/admin/auth/refresh`, `POST /api/admin/auth/logout`. 관리자 액세스 토큰은 기존 `TokenIssuer.issueAccessToken(adminAccountId, MemberRole.ADMIN)` 그대로(subject=admin_account.id, role=ADMIN). **갱신 토큰에 `role` 클레임을 추가**하고 `ParsedRefreshToken(memberId, jti, role)` 로 확장한다(구 토큰은 role 부재 → USER 로 해석해 하위 호환). 회원 `/api/auth/refresh` 는 role=ADMIN 갱신 토큰을 `INVALID_REFRESH_TOKEN` 으로 거부하고, 관리자 refresh 는 role=USER 를 거부한다. TTL 은 `kbap.auth.admin.{access-ttl: 1h, refresh-ttl: 7d}` 를 `JwtTokenProperties` 에 추가하고 발급 구현이 role 로 골라 쓴다(포트 시그니처 불변).
- **Rationale**: 갱신 토큰 저장소(`RedisRefreshTokenStore`, 값=id 문자열)는 주체 종류를 모른다. role 클레임 없이 관리자 refresh 를 넣으면 **관리자 jti 로 회원 `/api/auth/refresh` 를 호출해 `memberId=adminAccountId` 인 USER 액세스 토큰을 얻는 교차 발급**이 생긴다(FR-002 위반). 클레임 한 개로 양방향 거부가 가장 작은 수정이다.
- **Alternatives considered**: 관리자 전용 `AdminRefreshTokenStore`(키 접두 분리) — 포트·어댑터·조립 3조각 추가, 클레임 방식과 안전성 동일. 기각. 쿠키 기반 SPA 세션 — 별도 오리진 SPA 에서 `path=/admin` 쿠키 사용 불가. 기각.

### Decision 2 — 관리자 주체 식별과 기록 분리

- **Decision**: `JwtAuthenticationFilter` 가 role=ADMIN 이면 request 속성 `authAdminId`(기존 `authMemberId` 대신)와 MDC `adminId` 를 심는다. 관리자 컨트롤러는 새 `@AuthAdminId` 리졸버로 조작자 id 를 받는다(ADMIN 이 아니면 `ADMIN_FORBIDDEN`). 구 화면 인터셉터(`AdminPageAuthInterceptor`)도 쿠키 토큰 파싱 후 같은 속성을 심어 감사 기록 조작자를 공급한다. 회원 리졸버의 ADMIN 거부는 유지한다.
- **Rationale**: 현재 관리자 토큰 subject 가 `authMemberId` 속성으로 들어가 로그에서 회원 3 과 관리자 3 이 구분되지 않는다(인터뷰 인증 항목). 속성·MDC 키를 갈라 두면 필터 한 곳 수정으로 끝난다.
- **Alternatives considered**: JWT subject 를 `ADMIN:{id}` 로 인코딩 — 기존 토큰·파서·테스트 전부 영향. 기각.

### Decision 3 — 로그인 실패 잠금

- **Decision**: `common.port.auth.LoginAttemptStore { recordFailure(key): Int; reset(key); isLocked(key): Boolean }` seam + `api.infra.redis.RedisLoginAttemptStore`(키 `admin:login-fail:{loginId}`, INCR + TTL 15m). 5회 도달 시 `ADMIN_LOGIN_LOCKED`(AUTH-010, 403). 잠금 중 성공 비밀번호도 거부, 잠금 시간은 연장하지 않는다(FR-004).
- **Rationale**: 관리자 콘솔이 인터넷에 노출돼 있고 실패 제한이 없다. Redis 는 이미 refresh 저장에 쓰고 있어 어댑터 하나만 추가하면 된다(ADR-0018 패턴).
- **Alternatives considered**: `admin_account` 컬럼(fail_count, locked_until) — 계정 테이블 스키마 변경 + 매 시도마다 DB 쓰기. Redis TTL 이 자연 만료를 공짜로 준다. 기각.

### Decision 4 — 감사 이력 저장 방식

- **Decision**: 테이블 `admin_audit_log`(엔티티 `AdminAuditLog` — `common.domain.admin`), 컬럼 `admin_account_id, action(50), target_type(30), target_id, before_json(JSON), after_json(JSON), note(500)` + BaseEntity. 기록은 **AOP 가 아니라 명시 호출** — `api.admin.AdminAuditRecorder.record(adminId, action, targetType, targetId, before, after, note)` 를 각 관리자 쓰기 서비스가 같은 트랜잭션 안에서 호출한다. before/after 는 "변경된 필드만" 담은 Map 을 Jackson 으로 직렬화. 조회는 `GET /api/admin/audit-logs?targetType&targetId&adminAccountId&from&to&page&size`. 삭제/수정 API 없음(FR-008).
- **Rationale**: 관리자 쓰기 지점이 20개 안팎으로 유한하고, 각 지점이 "무엇이 바뀌었는지" 를 가장 잘 안다. AOP 는 before 스냅샷을 위해 엔티티 재조회·리플렉션이 필요해 오히려 복잡하다.
- **Alternatives considered**: Hibernate Envers — 전 엔티티 이력 테이블 + 관리자 외 변경도 섞임. 기각. 로그 파일 — 조회·보존 요구(FR-007/008) 미충족. 기각.

## Phase 1 — 음식 파이프라인

### Decision 5 — 콘텐츠 검증 규칙의 단일 출처

- **Decision**: `AdminFoodContentIngestRequest` 에 흩어진 검증(설명 1~255, 맵기 0~10, 9개 언어 전부, 재료 코드 카탈로그·1..100, 빈 배열 허용/누락 불허)을 `api.admin.FoodContentValidator.validate(candidate): List<FieldError>` 로 추출하고, 랭체인 적재·REST 수정(`PUT /api/admin/foods/{id}`)·구 화면 수정(`AdminFoodService.updateFood`) 세 경로가 같은 검증기를 쓴다. 위반은 `FOOD_INVALID_CONTENT`(FOOD-006, 400) + payload `{errors: [{field, code, message}]}`.
- **Rationale**: 인터뷰 사고 위험 1(재료 무검증 → 앱 상세 500). 규칙이 두 곳이면 다시 벌어진다(FR-019 "동일 규칙").
- **Alternatives considered**: Bean Validation 애너테이션을 수정 DTO 에 복제 — 카탈로그 코드 검증은 애너테이션으로 표현이 어렵고 복제가 곧 드리프트. 기각.

### Decision 6 — 상태 전이 규칙을 도메인에 둔다

- **Decision**: `Food` 에 `allowedTransitions(): Set<FoodTransition>` 과 전이 메서드를 둔다. 전이 집합(enum `FoodTransition`): `APPROVE`(PENDING_REVIEW→READY, 전제: `ingredients != null && imageRef != null`), `REJECT`(PENDING_REVIEW→FAILED, 사유 필수), `RESUBMIT`(FAILED→PENDING_REVIEW, 전제: 설명이 placeholder 가 아니고 재료 non-null 이고 이미지 있음 — 없으면 PENDING_IMAGE), `UNPUBLISH`(READY→PENDING_REVIEW, 벡터 DELETE 예약). 기존 `approve()` 에 전제 검사를 추가한다(랭체인 승인 경로도 같은 전제 — applyContent 가 이미지 없으면 PENDING_IMAGE 로 두므로 실제 영향 없음). `PUT` 수정은 `contentStatus` 를 받지 않는다(FR-020). FAILED 를 벗어나는 전이는 `contentFailureKind`/`contentReviewRejectionReason` 을 null 로(FR-022). `POST /api/admin/foods/{id}/transitions {transition, reason?}` 하나로 노출하고, `approve`/`reject` 는 같은 서비스의 별칭 엔드포인트로 둔다(React 버튼 매핑 편의).
- **Rationale**: 인터뷰 사고 위험 2(재료·이미지 없는 READY 노출). 헌법 IV — 상태 전이는 도메인 소유. 드롭다운 자유 대입(`AdminFoodService.updateFood` `contentStatus` 직접 세팅)을 제거한다.
- **Alternatives considered**: 관리자 "강제 전이" 별도 API(임의 상태 대입 + 감사) — 스펙 US2 시나리오 6/8 에 반한다. 기각. 전이 규칙을 서비스에 두기 — 헌법 IV 위반. 기각.

### Decision 7 — 목록 검색·삭제 포함 조회는 네이티브 프로젝션

- **Decision**: 음식·회원 관리자 목록은 `FoodAdminQueryRepositoryCustomImpl`/`MemberAdminQueryRepositoryCustomImpl`(각 도메인 패키지의 Custom 리포지토리, `EntityManager` 네이티브 SQL 동적 조립)이 **엔티티가 아닌 프로젝션 행**(`AdminFoodRow`, `AdminMemberRow`)을 돌려준다. 재료 검색은 `JSON_SEARCH(ingredients, 'one', :code, NULL, '$[*].code') IS NOT NULL`, 번역명 검색은 `JSON_SEARCH(name_translations, 'one', :kw...)`, id 검색은 `q` 가 숫자일 때. `includeDeleted`/`includeWithdrawn` 은 `status` 조건을 빼는 것으로 구현. 삭제 음식 상세·복구는 네이티브 `findByIdIncludingDeleted`·`restore(id)` UPDATE.
- **Rationale**: `@SQLRestriction("status='ACTIVE'")` 는 JPQL/파생 쿼리에서 우회 불가 — 삭제·탈퇴 행을 보려면 네이티브가 유일. JSON 컬럼 검색도 네이티브 함수. 정렬·필터 조합이 많아 파생 쿼리 폭발을 피한다(현재 4개 조합 × 4 메서드 = 8개).
- **Alternatives considered**: QueryDSL 도입 — 신규 의존·코드젠, JSON 함수는 결국 템플릿. 기각. `@Filter` 로 SQLRestriction 대체 — 전 엔티티 영향. 기각.

### Decision 8 — 상세 응답의 이력 동봉

- **Decision**: `GET /api/admin/foods/{id}` 는 음식 본체 + `contentOutboxes[]`(최근 10), `imageItems[]`(최근 10, 배치 정보 join), `vectorOutboxes[]`(최근 5), `reviewSummary{count, average}`, `scanMatchCount`, `bookmarkCount`, `auditLogs[]`(최근 10) 를 한 응답에 담는다. 신규 리포 메서드: `FoodContentOutboxJpaRepository.findTop10ByFoodIdOrderByIdDesc`, `ImageBatchItemJpaRepository.findTop10ByFoodIdOrderByIdDesc`, `FoodVectorOutboxJpaRepository.findTop5ByFoodIdOrderByIdDesc`, `ScanHistoryJpaRepository.countByFoodId`, `BookmarkJpaRepository.countByFoodId`, 리뷰는 기존 `aggregateRating(foodId, null)`.
- **Rationale**: 인터뷰 "상세에 파이프라인 이력 없음 — 세 화면을 이름으로 다시 찾음". 상세 1회 = 쿼리 8개 수준, 관리자 트래픽에서 허용.
- **Alternatives considered**: 이력을 별도 엔드포인트로 분리 — React 가 5번 호출. 상세는 한 번에, 목록형(배치·아웃박스)은 별도 페이지 API 로 이원화가 적정. 채택 혼합.

### Decision 9 — 이미지 재생성(READY 유지)과 업로드 교체

- **Decision**: (a) 재생성: `FoodImageBatchSubmitService.submitForFoods(ids)` 를 추가해 상태 무관 단건 배치를 만든다. 회수(`handleResult`)는 `Food.attachImage` 대신 새 `Food.replaceImage(ref)` — PENDING_IMAGE 면 PENDING_REVIEW 로, 그 외(READY 포함)는 상태 유지하고 imageRef 만 교체. 진행 중(PENDING 아이템 존재) 이면 `IMAGE_REGENERATION_IN_PROGRESS`(FOOD-009, 409). (b) 업로드: `POST /api/admin/foods/{id}/image/upload-url {contentType, contentLength}` → 기존 `PresignedUploadPort`(새 `UploadPurpose.FOOD("food")`, 키 `{prefix}/images/food/{yyyy}/{MM}/admin{adminId}_{uuid}.{ext}`) → `PUT /api/admin/foods/{id}/image {objectKey}` 가 `StorageObjectStore.head` 로 존재·형식 확인 후 `replaceImage`. READY 음식은 벡터 UPSERT 재예약(이미지 URL 메타 갱신).
- **Rationale**: 인터뷰 사고 위험·SC-006(교체 중 노출 중단 0). 기존 업로드 규약(사전 서명 2단계·형식/크기 프로퍼티) 재사용.
- **Alternatives considered**: 관리자 multipart 직접 업로드 — 서버 경유 10MB 업로드, 기존 규약과 이질. 기각.

### Decision 10 — 배치 즉시 회수·아이템 재제출

- **Decision**: `POST /api/admin/foods/images/collect` 는 ShedLock `LockingTaskExecutor.executeWithLock("food-image-collect", …)` 로 `collectSubmitted()` 를 감싼다 — 스케줄 실행과 겹치면 락 획득 실패 → `IMAGE_COLLECT_IN_PROGRESS`(FOOD-008, 409). 재제출: `POST /api/admin/foods/images/items/resubmit {itemIds}` → 대상 음식들로 `submitForFoods`. 배치 상세 `GET /api/admin/foods/images/{batchId}` 는 아이템 + `openaiBatchId`·`promptVersion`·`model`.
- **Rationale**: 수동 회수가 3시간 cron 과 동시에 돌면 같은 결과를 두 번 처리할 수 있다 — 기존 락 이름을 그대로 써 상호 배제. 
- **Alternatives considered**: 락 없이 멱등성에 의존(`reserveFileName`) — 배치 close 가 두 번 시도되는 경합 잔존. 기각.

### Decision 11 — 콘텐츠 아웃박스 고착·재발행·취소

- **Decision**: `food_content_outbox` 에 `last_error`(500)·`last_failed_at` 추가, 상태 enum 에 `CANCELED` 추가(마이그레이션 ALTER ENUM). batch `FoodContentOutboxPublisher` 의 `recordPublishFailed` 가 예외 메시지를 함께 기록하도록 시그니처 확장. 조회 `GET /api/admin/foods/content-outboxes?status&stuckHours=3&foodId&page&size`(고착 = SENT ∧ sent_at < now − stuckHours). `POST …/{id}/requeue`: SENT→PENDING, sent_at=null(재발행 시 새로 찍힘). `POST …/{id}/cancel`: PENDING/SENT→CANCELED. 랭체인 콜백이 CANCELED 요청으로 오면 기존 `completeIfProcessable`(PENDING/SENT 만) 이 0 을 돌려 `INVALID_REQUEST` 로 거절된다 — 의도된 무시.
- **Rationale**: 인터뷰 사고 위험 8(어제 실제 발생). 발행 실패 사유가 지금은 카운트에 묻힌다.
- **Alternatives considered**: 고착 요청을 자동 재발행하는 배치 — 원인(콜백 대상 장애) 이 남아 있으면 무한 재시도. 사람이 판단하는 조작으로 둔다. 기각.

### Decision 12 — 벡터 아웃박스 일괄 재시도·적재 결과

- **Decision**: `FoodVectorOutboxJpaRepository.retryAllFailed(): Int` 네이티브 UPDATE(FAILED→PENDING, attempts=0, last_error=null). `POST /api/admin/foods/vector-outboxes/enqueue` 응답 `{enqueued, remaining}`(remaining = 적재 후 `countReadyWithoutVectorUpsertOutbox`). 목록 `GET …/vector-outboxes?status&page&size` 는 파생 쿼리 `findByOutboxStatus(status, pageable)`.
- **Rationale**: 20건씩 200번 클릭(인터뷰). UPDATE 한 문장이면 끝.

### Decision 13 — 대시보드 기간·라벨·비용 범위

- **Decision**: `GET /api/admin/dashboard?days=7`(1..90). `heightPct`·`dayLabel` 제거. 상태는 `{code, label}` 쌍(`FoodContentStatus.label` 확장 — 한국어 표시명 1곳). 비용은 `DailyModelCostSum` 에 `costKrw` 합계 추가, 응답에 `scopeNote: "스캔 비전 + 이미지 생성 성공분만 집계(임베딩·실패분 제외)"` 고정 문자열. 아웃박스 요약에 `stuckCount`(Decision 11 기준) 추가.
- **Rationale**: 인터뷰 대시보드 항목 전부. 라벨 단일 출처는 enum 이 자연스럽다(헌법 V 의 "식별자 enum + 개발자 가독 label" 과 같은 성격 — 여기선 사용자 노출용이므로 `displayName` 으로 명명).

### Decision 14 — 시드 응답 상세·삭제 충돌 분류

- **Decision**: `AdminFoodService.seedIncomplete` 반환을 `SeedIncompleteResult(requested, created, skipped, createdIds, skippedNames, blockedByDeletedNames)` 로 확장. 삭제 충돌은 네이티브 `findKoreanNamesIncludingDeleted(names)` 로 판정. 기존 `POST /api/admin/foods` 응답에 필드 추가(하위 호환 — 필드 추가만).
- **Rationale**: FR-033, 인터뷰 시드 항목.

## Phase 2 — 회원

### Decision 15 — 제재 상태·정지 전용 오류

- **Decision**: `member` 에 `suspended_at`, `suspend_reason`(500) 추가. `PATCH /api/admin/members/{id}/status {memberStatus, reason}` → `Member.suspend(reason)`/`Member.reinstate()`. `MemberService.findOrSignUp` 은 상태 무관으로 먼저 찾고 SUSPENDED 면 `MEMBER_SUSPENDED`(MEMBER-012, 403)를 던진다(현재는 ACTIVE 만 찾아 재가입 시도 → unique 충돌 → `DUPLICATE_SOCIAL_IDENTITY` 오염). `MemberService.getMember` 도 SUSPENDED 면 같은 코드(기존 토큰으로 회원 API 호출 시 즉시 거부 — 필터에서 DB 조회는 하지 않는다).
- **Rationale**: 인터뷰 사고 위험 6, FR-037. 정지자 거부를 필터에 두면 매 요청 DB 조회.
- **Alternatives considered**: 정지 시 refresh 토큰 강제 폐기 — jti 를 회원별로 역조회할 수 없다(키가 jti). 액세스 만료(30m) 안에 `getMember` 거부로 충분. 기각.

### Decision 16 — 회원 상세 집계·최근 활동

- **Decision**: 신규 count 쿼리 — `ReviewJpaRepository.countByMemberId`, `OrderJpaRepository.countByMemberId`, `ScanHistoryJpaRepository.countByMemberId`, `BookmarkJpaRepository.countByMemberId`, `MemberBlockJpaRepository.countByBlockerMemberId`, `ReportJpaRepository.countByReporterMemberId`, `ReportJpaRepository.countReceivedByMemberId`(신고 대상 리뷰의 작성자 join, 네이티브). 최근 5건은 기존 페이지 쿼리 재사용(`findMemberReviewPage`, `findPageByMemberId`, `findScannedFoodPageIds`). 랭킹은 `Member.ranking`(score·tier·pointsToNext 계산 존재). 원장은 `MemberRankingEventJpaRepository.findByMemberIdOrderByIdDesc(pageable)` 추가. `providerUid` 는 응답에서 제거, `email` 은 `ab***@domain` 마스킹.
- **Rationale**: FR-035·041. 카운트 컬럼(`scanCount` 등)은 잔액이지 실제 행 수가 아니라 별도 count 로 정합을 보여준다.

### Decision 17 — 강제 탈퇴·실패 기록

- **Decision**: `DELETE /api/admin/members/{id}` → `AuthService.withdraw(memberId)` 재사용(소셜 삭제 선행 → 실패 시 AUTH-007). 실패도 감사 이력에 `action=MEMBER_WITHDRAW_FAILED, note=사유` 로 남기고, `GET /api/admin/audit-logs?action=MEMBER_WITHDRAW_FAILED` 로 조회한다 — 별도 실패 테이블을 만들지 않는다. 이미 탈퇴(찾을 수 없음)면 200 멱등.
- **Rationale**: FR-040 의 "기록·조회" 를 감사 이력이 이미 충족. 테이블 추가는 중복.

### Decision 18 — 프로필 초기화·scan-unlock

- **Decision**: `PATCH /api/admin/members/{id}/profile {resetNickname?: true, resetProfileImage?: true}` → 닉네임 `"사용자{id}"`, 프로필 이미지 null. `POST /api/admin/members/{id}/scan-unlock` → `Member.unlockScan()`(scanUnlocked=true). 둘 다 감사 기록.

## 공통

### Decision 19 — 에러 코드 신규

| 코드 | 상태 | 용도 |
|---|---|---|
| `ADMIN_LOGIN_FAILED` AUTH-009 | 401 | 아이디/비밀번호 불일치 |
| `ADMIN_LOGIN_LOCKED` AUTH-010 | 403 | 5회 실패 잠금 중 |
| `MEMBER_SUSPENDED` MEMBER-012 | 403 | 정지 회원 로그인·회원 API |
| `FOOD_INVALID_TRANSITION` FOOD-005 | 409 | 허용되지 않는 전이(payload: allowed[]) |
| `FOOD_INVALID_CONTENT` FOOD-006 | 400 | 콘텐츠 검증 실패(payload: errors[]) |
| `DUPLICATE_FOOD_NAME` FOOD-007 | 409 | 수정/복구 시 이름 충돌 |
| `IMAGE_COLLECT_IN_PROGRESS` FOOD-008 | 409 | 회수 락 점유 중 |
| `IMAGE_REGENERATION_IN_PROGRESS` FOOD-009 | 409 | 진행 중 아이템 존재 |
| `FOOD_CONTENT_REQUEST_NOT_PENDING` FOOD-010 | 409 | requeue/cancel 대상 상태 부적합 |

버전 충돌은 기존 `CONFLICT`(COMMON-004, 409) 재사용(`OptimisticLockingFailureException` 핸들링 존재 + 명시 비교 시 같은 코드). 일괄 상한 초과·기간 범위 초과는 `INVALID_REQUEST`(COMMON-002).

### Decision 20 — Swagger 관리자 그룹·경로 규약

- **Decision**: `OpenApiConfig` 에 `GroupedOpenApi.builder().group("admin").pathsToMatch("/api/admin/**")` 추가. 모든 신 컨트롤러는 `ApiPaths.ADMIN` 하위, `version = "1.0+"`, `*Api` 인터페이스에 swagger 만, 컨트롤러에 Spring 애너테이션(프로젝트 규약). JWT 필터 패턴 `/api/admin/*` 이 하위 전 경로를 덮으므로 추가 등록 불필요.

### Decision 21 — 구 화면 최소 수정

- **Decision**: (a) `food-list.html` 편집 폼에 `<input type="hidden" name="version">` 추가, 맵기 `max="10"`; (b) `AdminFoodPageController.updateFood` 가 `version` 을 받아 `AdminFoodService.updateFood(id, command, expectedVersion)` 로 전달(불일치 `error=stale`); (c) `updateFood` 가 Decision 5 검증기를 쓰고 `contentStatus` 파라미터를 무시(드롭다운은 읽기 전용 표시로 전환 — 상태 변경은 신 화면/REST); (d) 승인 대기 음식 상세에 승인/반려 폼 2개 추가(구 화면 운영 공백 방지 — `POST /admin/foods/{id}/approve|reject`); (e) 구 화면 조작도 감사 기록. 그 외 템플릿·경로는 무변경.
- **Rationale**: US7 + Assumptions "버전 제출·맵기 범위·검증 공유는 예외". (c)(d) 는 사고 위험 2 를 구 화면에서도 닫기 위해 필요 — 드롭다운을 살려두면 백도어가 남는다.
- **Alternatives considered**: 구 화면 완전 무수정 — 백도어 잔존, 규칙 이중화. 기각.

### Decision 22 — 테스트 전략

- **Decision**: 신 REST 는 컨트롤러 통합 테스트(`@SpringBootTest` + MockMvc + Testcontainers, BehaviorSpec) 를 엔드포인트 묶음별 1파일. 도메인 전이(`Food.allowedTransitions`/`replaceImage`)와 검증기(`FoodContentValidator`)는 순수 단위 테스트. 관리자 토큰 헬퍼는 테스트 공용 `AdminTestTokens`(api test fixtures) 로 통합해 20개 파일의 로컬 헬퍼 중복을 더 늘리지 않는다. 구 화면 회귀는 기존 7개 페이지 테스트 그대로 + 버전/승인 폼 시나리오 추가.

## 구현 중 확정된 편차 (2026-08-25)

- **D5 보완 — 부분 콘텐츠 허용**: 검증기에 `requireComplete` 를 두어 READY/PENDING_REVIEW 만 완성 규칙(번역 9개·재료 필수·설명 필수·맵기 0~10)을 요구한다. FAILED/PENDING_IMAGE 는 이름 교정 같은 운영이 막히지 않게 부분 콘텐츠를 허용하되 재료 코드·비율·길이 규칙은 항상 적용한다(앱 상세 500 방지 목적은 유지).
- **D9 보완**: 회수(`handleResult`)는 상태 무관으로 `Food.replaceImage` 를 적용한다 — 제출 후 상태가 바뀐 음식도 이미지는 받는다(아이템 DONE). 기존 "PENDING_IMAGE 아니면 아이템 실패" 규칙 폐지.
- **복구 이름 충돌 없음**: `uq_food_korean_name` 이 삭제 행에도 걸려 동명 활성 음식이 생길 수 없다 → `FOOD-007` 복구 분기 제거.
- **조건 일괄 재수집 필터**: 기존 `q`·`status` 만 유지(재료·실패유형 필터는 개별 재수집이 대체 — YAGNI).
- **`contentStatus` 거절**: Jackson 전역 설정이 미지 필드를 무시하므로 `@field:Null` 로 명시 거절(400 COMMON-002).
- **구 화면 페이지 테스트**: 페이지 로그인이 Redis(갱신 토큰·잠금)를 쓰게 되어 `AdminPageControllerTest` 에 `RedisContainerConfig` 를 추가했다.
