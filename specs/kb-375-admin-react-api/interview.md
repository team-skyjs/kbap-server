# 구 관리자 페이지 인터뷰 종합 — 관리자 API 개선 백로그

기준: develop `cc8a7bf2` (2026-08-25). 서브에이전트 2개가 운영자 관점으로 Thymeleaf 관리자 화면·컨트롤러·서비스·Flyway 스키마(49개 누적)를 감사한 결과를 종합했다. 원본 보고서 2편은 본문 아래에 그대로 붙였다.

## 한 줄 결론

관리자 페이지는 **"음식 콘텐츠 파이프라인을 들여다보는 창"** 까지만 만들어져 있고, (1) 파이프라인에 **개입**하는 정식 수단(승인·반려·개별 재수집·이미지 교체·배치 회수)이 없어 상태 드롭다운 백도어로 운영되며, (2) 회원·리뷰·신고·주문·커뮤니티는 **DB 에 쌓이기만 하고 관리자가 읽지도 조치하지도 못한다.** React 전환은 이 두 축을 REST 로 여는 작업이 본체다.

## 사고 위험 (지금 당장 고쳐야 하는 것)

| # | 문제 | 왜 사고인가 | 근거 |
|---|---|---|---|
| 1 | 재료 JSON 무검증 저장 | 카탈로그 밖 코드·0% 저장 시 **앱 사용자 음식 상세 500** | `AdminFoodService.kt:74-82`, `RiskLevel.kt:17` |
| 2 | 상태 드롭다운이 전이 규칙 우회 | 재료·이미지 없는 음식이 READY 로 앱 노출(위험도 UNKNOWN) | `AdminFoodService.kt:96`, `Food.kt:149` |
| 3 | 맵기 `max="5"` | 맵기 6~10 음식은 편집 저장 자체가 불가 | `food-list.html:136` vs CHECK -1..10 |
| 4 | 감사 로그 부재 | 앱 버전 PUT 오설정 = 전 사용자 잠금인데 누가/언제/이전값 없음 | `AdminAppVersionService.kt:19-27` |
| 5 | 신고가 쓰기만 되고 아무도 안 읽음 | 신고 접수 → 방치. 처리 상태 컬럼도 없음 | `V2026.08.01.05.16.32`, `ReportJpaRepository.kt` |
| 6 | 회원 정지 수단 없음 + 정지자 409 오염 | prod DB 직접 UPDATE 로만 제재, 정지자는 "중복 계정" 에러 | `MemberStatus.kt`, 위키 `member-auth.md` |
| 7 | 재수집이 contains 일괄뿐 | "김치" 로 좁혀도 수십 건이 SQS·LLM 비용으로 나감 | `AdminFoodService.kt:119-146` |
| 8 | 고착 SENT 아웃박스 식별 불가 | Lambda DLQ 이탈이 조용히 누적(어제 실제 발생) | `AdminFoodOutboxQueryService.kt:15-21` |

## 관리자 API 개선 백로그 (React 전환 순서 제안)

### Phase 0 — 전제 (없으면 SPA 자체가 불가)
- `POST /api/admin/auth/login {id,password} → {accessToken}` — 쿠키(`path=/admin`) 로그인만 있어 SPA 가 토큰을 못 얻음. 관리자 TTL 은 30m → 별도(8h) 또는 refresh.
- 관리자 토큰 클레임 분리(`sub=ADMIN:{id}`, MDC `adminId`) + 필터 수준에서 `/api/admin` 외 경로의 ADMIN 롤 거부.
- `admin_audit_log` 테이블 + 관리자 쓰기 서비스 공통 기록(action·target·before/after JSON·admin_account_id).
- 로그인 실패 5회 잠금(Redis 카운터), CORS origin 축소.

### Phase 1 — 음식 파이프라인 개입 (사고 위험 1·2·3·7·8 해소)
| 엔드포인트 | 대체하는 것 |
|---|---|
| `GET /api/admin/foods?page&size&q&status&failureKind&ingredient&sort` | 200 고정·contains 검색·id DESC 고정. `q` 숫자면 id |
| `GET /api/admin/foods/{id}` — 구조화 필드 + `outboxes[]`·`imageItems[]`·`vectorOutbox`·`reviewSummary`·`scanMatchCount`·`contentReviewAttempts`·`longDescription`·`koreanName` | JSON 문자열 textarea, 이력 없음 |
| `PUT /api/admin/foods/{id}` — **`version` 필수**, 재료 카탈로그·1..100 검증, 상태는 받지 않음, FAILED 이탈 시 실패 필드 클리어 | 무검증·낙관락 무력·실패 사유 잔존 |
| `POST /api/admin/foods/{id}/approve` · `/reject {reason}` (`Food.approve/reject`) | 상태 드롭다운 백도어 |
| `POST /api/admin/foods/{id}/transition {to, reason}` — 허용 전이만, 감사 로그 필수 | 강제 전이 |
| `POST /api/admin/foods/{id}/recollect` | contains 일괄 |
| `POST /api/admin/foods/{id}/images/regenerate` (READY 유지) · `PUT /api/admin/foods/{id}/image` (presigned 업로드) | PENDING_IMAGE 강등 후 3h 대기 |
| `DELETE /api/admin/foods/{id}` · `POST …/restore` · `?includeDeleted` | 복구 불가·이름 영구 점유 |
| `POST /api/admin/foods/bulk {ids, action}` | 없음 |
| `GET /api/admin/foods/images` (페이지) · `GET …/images/{batchId}` (아이템+error_msg+openai_batch_id) · `POST …/images/collect` · `POST …/images/items/{id}/resubmit` | 배치 카운트만·3h cron 대기 |
| `GET /api/admin/foods/content-outboxes?status&stuckHours=` · `POST …/{id}/requeue` · `/cancel` (+ `last_error`·`last_failed_at` 컬럼) | 고착 SENT 불가시 |
| `POST /api/admin/foods/vector-outboxes/retry-all-failed` · `GET …?status&page` · enqueue 응답 `{enqueued, remaining}` | 1건씩·피드백 없음 |
| `POST /api/admin/foods/seed` 응답에 `createdIds[]`·`skippedNames[]`·`blockedByDeleted[]` | 카운트만 |
| `GET /api/admin/dashboard?days=` — 상태 라벨 단일 매핑, LLM 비용 집계 범위 명시·KRW 병기, "활성 회원 수" | 7일 고정·과소집계 미고지 |
| `GET /api/admin/ingredients` (카탈로그 읽기 — 재료 편집기 select 소스) | enum 암기 |

### Phase 2 — 회원 운영
| 엔드포인트 | 비고 |
|---|---|
| `GET /api/admin/members?nickname&email&provider&memberStatus&onboarding&createdFrom&createdTo&includeWithdrawn&sort&size` | 검색 0개 → 전부 |
| `GET /api/admin/members/{id}` — `providerUid`/`email` 마스킹, + `scanUnlocked`·`isScanAllowed`·`dietCategories`·`currency`·`updatedAt`·`ranking{score,tier,pointsToNext,uniqueReviewedFoodCount}`·`recentScans[]`·`recentReviews[]`·`recentOrders[]`·`reportsReceived/Filed`·`blocksCount`·`bookmarkCount` | 활동 이력 0 |
| `PATCH /api/admin/members/{id}/status {memberStatus, reason}` (+ `suspended_at`·`suspend_reason` 컬럼, 정지자 로그인 에러 코드 신설) | 제재 불가 |
| `PATCH /api/admin/members/{id}/profile {nickname?, profileImageReset?}` · `POST …/scan-unlock` · `DELETE /api/admin/members/{id}` | 조작 0 |
| `GET /api/admin/members/{id}/ranking-events` | 원장 |
| `GET /api/admin/members/withdrawal-failures` · `POST …/{id}/retry` (실패 기록 테이블 선행) | 소셜 삭제 실패 로그에만 |

### Phase 3 — 관리자에 없던 도메인
| 도메인 | 엔드포인트 | 선행 스키마 |
|---|---|---|
| 신고 | `GET /api/admin/reports?status&reason&targetType&sort=countDesc` · `GET …/{id}` · `PATCH …/{id} {result, note}` | `report.handled_status/handled_by/handled_at/handle_note`; `ReportTargetType` 에 POST/COMMENT |
| 리뷰 | `GET /api/admin/reviews?foodId&memberId&minRating&hasImage&reported` · `DELETE …/{id}`(랭킹 차감 재사용) · `PATCH …/{id}/images` · `PATCH …/{id}/place {addressKo}` | — |
| 커뮤니티 | `GET /api/admin/community/posts?…` · `GET …/{id}/comments` · `DELETE …/posts/{id}` · `/comments/{id}` | 복구형 블라인드면 `visibility` |
| 주문 | `GET /api/admin/orders?memberId&foodId&from&to` · `GET …/{id}` · `DELETE …/{id}` | — |
| 스캔 | `GET /api/admin/scans?memberId&foodId&unmatched&from&to` | — |
| 앱 버전 | 기존 GET/PUT + `GET /api/admin/app-version/history` | `app_version_history` 또는 감사 로그로 대체 |
| 관리자 계정 | `GET/POST /api/admin/accounts` · `PATCH …/me/password` · `DELETE …/{id}` | `last_login_at`·`password_changed_at` |

### 필요한 마이그레이션 요약
`admin_audit_log`(신규) · `report.handled_*`(4컬럼) · `member.suspended_at/suspend_reason` · `food_content_outbox.last_error/last_failed_at` · `admin_account.last_login_at/password_changed_at` · (선택) `community_*.visibility`, `member_withdrawal_failure`

## React 화면 설계에 바로 반영할 UX 결론
- 상세는 **목록 유지 + 우측 패널**, 상세/편집 진입이 URL 리로드가 아니어야 함(현재 200건 재렌더 + 뒤로가기 3단계).
- 편집 실패 시 **입력값 보존**(현재 redirect 로 전량 유실) — REST 400 응답을 폼에 매핑.
- 번역 9개 언어·재료는 JSON textarea 가 아니라 **언어 그리드 + 재료 select(카탈로그)**.
- 상태 라벨은 **단일 매핑**(대시보드 한국어 vs 목록 enum 원문 불일치 해소).
- 대시보드 카드 → 해당 필터 목록으로 **클릭 이동**.
- `providerUid`·`email`·`imageRef`·`version` 은 **기본 숨김/마스킹**.

---
---

# 음식/콘텐츠 관리자 화면 인터뷰 결과

전제: 운영자는 브라우저 쿠키 세션(`AdminPageAuthInterceptor`)으로만 화면을 쓴다. REST 관리자 API(`/api/admin/**`)는 ADMIN JWT Bearer 가 필요해 화면에서 호출되지 않는다 — 화면에 버튼이 없으면 운영자는 그 기능을 쓸 수 없다.

## 화면별 불편점

### 1. 대시보드 (/admin/foods)

- [P1] 승인 대기(PENDING_REVIEW) 건수는 보이는데 화면에서 승인·반려를 할 수 없다 — 근거: `foods.html:22-25` 카드만 있고 템플릿에 approve/content-reviews 0건; 승인/반려 API 는 REST 전용 `AdminFoodContentReviewController.kt:26-37`. 시나리오: "승인 대기 37건"을 처리하려면 상세 편집에서 상태 드롭다운을 READY 로 바꿔야 하는데, 이 경로는 반려 사유·`content_review_attempts` 를 기록하지 않는다(`Food.reject` 우회, `AdminFoodService.kt:96`). 제안: 상세 패널에 승인/반려(사유) 버튼 → `POST /admin/foods/{id}/approve|reject` 가 `Food.approve/reject` 호출.
- [P1] 콘텐츠 아웃박스에서 "발행됐는데 결과가 안 돌아온" 건(고착 SENT)을 식별할 수 없다 — 근거: `AdminFoodOutboxQueryService.kt:15-21` 상태별 카운트 + 최근 20건뿐, 테이블에 실패 상태·에러 컬럼 없음(`V2026.08.11.04.27.20:10-12`, `V2026.08.12.00.00.00`). 시나리오: 랭체인 Lambda 가 DLQ 로 빠져도 SENT 카운트만 쌓이고, "발행됨" 3일째인 걸 최근 20건에 우연히 걸려야 안다. 제안: `sent_at < now-N시간 AND SENT` 고착 카운트 + 목록, 행별 재발행(PENDING 복귀)/취소.
- [P1] LLM 비용 지표가 실제 비용 일부만 집계된 값인데 "최근 7일 LLM 호출 비용"으로 전체처럼 보인다 — 근거: `foods.html:190`, 위키 `llm-architecture.md:36-38`(스캔 vision + 이미지 attach 성공분만, 임베딩 배치 미기록). 제안: 집계 범위 문구 + 배치 미터링 저장 경로 추가.
- [P2] 상태 카드 클릭 불가 — `foods.html:9-30`. 제안: 카드 → `/admin/foods/list?status=…` 링크.
- [P2] 상태 명칭이 화면마다 다름 — 대시보드 한국어(`foods.html:15-27`) vs 목록/편집 enum 원문(`food-list.html:13-14, 57, 130-131`). 제안: 뷰 모델 `label` 단일 매핑.
- [P2] 아웃박스 `attempts` 가 성공·실패 양쪽에서 증가(`FoodContentOutboxJpaRepository.kt:62-93`) — "시도 3" 의미 불명(`foods.html:67, 82`). 제안: `last_error`, `last_failed_at` 추가.
- [P2] 벡터 실패 재처리 1건씩만 — `foods.html:143-145`, `AdminFoodDashboardService.kt:57-63`. 제안: `POST /admin/foods/vector-outboxes/retry-all-failed`.
- [P2] 벡터 실패 목록 20건 고정, 카운트와 불일치 — `FoodVectorOutboxJpaRepository.kt:32`, `foods.html:115-118`. 제안: 페이지네이션.
- [P2] "미적재 READY 벡터 적재" 결과 피드백 없음 — `AdminFoodPageController.kt:34-38` 무조건 redirect; ENQUEUE_MAX 500 초과 잔여도 안 보임. 제안: `?enqueued=N` + 잔여 안내.
- [P2] 지표 7일 고정 — `AdminDashboardMetricsService.kt:25-26`. 제안: `?days=`/from-to.
- [P3] "총 가입자 수"가 ACTIVE 만 — `AdminDashboardMetricsService.kt:28`, `foods.html:158`. 제안: 라벨 "활성 회원 수".
- [P3] `cost_krw` 있는데 USD 만 표시 — `V2026.07.17.22.58.05:6-7`. 제안: KRW 병기(고정환율 명시).
- [P3] 파이프라인 병목(상태별 체류 시간, 일별 READY 전이) 불가 — 스냅샷 카운트만(`AdminFoodDashboardService.kt:20-35`). 제안: 상태 전이 이력 또는 `updated_at` 기반 전이 카운트.

### 2. 음식 목록/상세/편집 (/admin/foods/list)

- [P1] 맵기 입력 `max="5"` 인데 실제 범위 -1..10 — `food-list.html:136` vs CHECK `V2026.07.22.14.27.32:4`, 적재 검증 0..10(`AdminFoodContentIngestRequest.kt:79`). 시나리오: 맵기 7 음식은 이름만 고쳐도 브라우저 rangeOverflow 로 저장 불가. 제안: `max="10"`.
- [P1] 재료 JSON 이 카탈로그 코드·확률 범위 검증 없이 저장 — `AdminFoodService.kt:74-82` 파싱만; 적재 API 는 검증(`AdminFoodContentIngestRequest.kt:56-63`); `FoodIngredient.riskLevel()` 1..100 밖 `require` 실패(`RiskLevel.kt:17`). 시나리오: `"PEANUTS"` 오타·`inclusion_percent: 0` 저장 → 앱 상세 500. 제안: `updateFood` 에 동일 검증 + `invalid-ingredient`.
- [P1] 편집 저장 실패 시 입력값 전량 유실 — `AdminFoodPageController.kt:91-96` redirect 후 DB 값 재렌더(`AdminFoodService.kt:61-64`). 제안: 실패 시 입력값 유지 재렌더.
- [P1] 상태 드롭다운이 상태 머신 백도어 — `food-list.html:129-132`, `AdminFoodService.kt:96` 직접 대입. 시나리오: FAILED(`ingredients=null`) → READY 면 앱에서 위험도 UNKNOWN(`Food.kt:149-150`) 노출, 이미지 없는데 READY 가능. 제안: 허용 전이 버튼(승인·반려·재제출)만, 강제 전이는 확인+감사 로그.
- [P1] 개별 음식 재수집 없음 — 조건 일괄뿐(`AdminFoodPageController.kt:127-146`), contains 검색(`FoodJpaRepository.kt:71`)이라 과포함. 제안: `POST /admin/foods/{id}/recollect`.
- [P1] 개별 이미지 재생성/교체 없음 — 후보는 PENDING_IMAGE 만(`FoodJpaRepository.kt:94-105`), 상세엔 `imageRef` 텍스트(`food-list.html:140-142`). 시나리오: READY 사진 교체하려면 앱 노출 끊고 최대 3시간+ 대기. 제안: `POST /admin/foods/{id}/images/regenerate`(READY 유지) + 업로드(`PresignedUploadPort`).
- [P2] 낙관적 락 무력 — `version` 표시만(`food-list.html:166`), 폼 미제출(`AdminFoodPageController.kt:63-76`). 제안: hidden `version` → 불일치 `error=stale`.
- [P2] 실패 유형·사유가 편집 후에도 잔존 — `updateFood` 가 `contentFailureKind`/`contentReviewRejectionReason` 미초기화(`AdminFoodService.kt:91-104`). 제안: FAILED 밖으로 옮길 때 클리어.
- [P2] 폼 검증 밖 예외가 JSON 으로 노출 — 255자 초과 DB 예외, 맵기 문자 입력 → `@RestControllerAdvice`(`GlobalExceptionHandler.kt:18`) JSON 원문; 폼 `maxlength` 없음(`food-list.html:124, 146`). 제안: `maxlength` + 관리자용 HTML 에러 뷰.
- [P2] 목록 카드 컬럼 부족 — id·맵기·수정일 뷰 모델엔 있으나 미렌더(`AdminFoodService.kt:232-240` vs `food-list.html:44-61`); 실패 유형 INGREDIENT_GUARD 만 배지(`:58`); 재수집 횟수·리뷰수/평점·벡터 상태 없음. 제안: 표 뷰 또는 메타 1줄.
- [P2] 정렬 id DESC·페이지 200 고정 — `AdminFoodService.kt:41, 183`. 제안: `sort=`, `size=`.
- [P2] 검색 display_name contains 뿐 — `AdminFoodService.kt:42-47`. 제안: 숫자면 id, `ingredient=CODE`, `lang` 검색.
- [P2] 상세가 `?detail=` GET 리로드 `<dialog>` — 200건 재렌더(`food-list.html:85-86, 198-205`), 뒤로가기 3단계. 제안: 상세 별도 fragment/JSON.
- [P2] 번역 9개·재료를 JSON textarea 로 편집 — `food-list.html:150-163`. 제안: 언어별 input + 재료 select/검색.
- [P2] 삭제 음식 조회/복구 불가, 이름 영구 점유 — `@SQLRestriction`, `food-list.html:176-178`. 제안: `includeDeleted` + `restore`.
- [P2] 상세에 파이프라인 이력 없음 — `AdminFoodDetailView`(`AdminFoodService.kt:255-292`). 제안: `outboxes[]`, `imageItems[]`, `vectorOutboxes[]` 동봉.
- [P3] `longDescription`·`content_review_attempts`·`korean_name` 상세 미노출. 제안: 읽기 전용 추가.
- [P3] 재수집 문구에 "READY 는 노출 유지" 없음(`food-list.html:20`).

### 3. 시드 (/admin/foods/seed)

- [P2] 결과가 카운트뿐 — 어떤 이름이 skip 됐는지 모름(`AdminFoodPageController.kt:151-168`). 제안: `skippedNames[]`·`foodIds[]`.
- [P2] 실패 시 textarea 입력 유실(`:154-166`).
- [P2] "FAILED 로 등록" 설명이 절반만 맞음(`food-seed.html:7`) — 실제로는 즉시 자동 수집 아웃박스 생성(`FoodService.kt:160-171`). 제안: 문구 정정.
- [P3] 소프트 삭제 동명 충돌이 `skipped` 로 합산(`FoodService.kt:156-159`). 제안: `blockedByDeleted[]`.

### 4. 이미지 배치 (/admin/foods/images)

- [P1] 아이템 단위 실패 사유 불가시 — `image_batch_item.error_msg`/`file_name`(`V2026.07.24.14.00.01:26-27`) 있으나 배치 카운트만(`AdminImageBatchQueryService.kt:39-49`, `food-images.html:30-39`). 제안: `GET /admin/foods/images/{batchId}` 아이템 목록.
- [P1] 수동 회수 트리거 없음 — 3시간 cron 뿐(`FoodImageBatchCollectService.kt:42-44`). 제안: `POST /admin/foods/images/collect`.
- [P2] "이미지 미보유 음식 전체 대상" 문구 오류(`food-images.html:16`) — 실제 PENDING_IMAGE + 진행 중 아이템 없음만(`FoodJpaRepository.kt:94-105`). 제안: 후보 건수 표시 + 문구 정정.
- [P2] `openai_batch_id`·`prompt_version` 미노출. 제안: 노출.
- [P2] 실패 아이템 재제출 불명확 — 상태 바뀐 음식은 영영 제외(`AdminApi.kt:67`). 제안: `POST …/items/{id}/resubmit`.
- [P3] 최근 20건 고정(`AdminImageBatchQueryService.kt:35`).

## DB 에 있으나 화면/API 에 노출되지 않는 정보

| 테이블.컬럼 | 의미 | 어디에 노출 |
|---|---|---|
| food.content_review_attempts | 반려/실패 누적 | 목록 메타·상세 |
| food.content_failure_kind (NOT_FOOD·JUDGE_REJECTED) | 실패 유형 — 목록은 INGREDIENT_GUARD 만 | 배지 3종 + `failureKind=` 필터 |
| food.long_description | 벡터 메타 설명 | 상세 읽기 전용 |
| food.korean_name | 매칭키 | 상세(중복 진단) |
| food.spiciness / updated_at | 뷰 모델엔 있으나 미렌더 | 목록 메타 |
| food.version | 폼 미제출 | hidden → 충돌 감지 |
| food_content_outbox.sent_at 경과 | 고착 SENT | 대시보드 카운트/목록 |
| food_content_outbox (음식별) | 요청·발행·완료 시각 | 음식 상세 |
| food_vector_outbox.outbox_status/last_error | 검색 반영 여부 | 목록 아이콘·상세 |
| image_batch.openai_batch_id, prompt_version | 외부 추적 키 | 배치 목록 |
| image_batch_item.error_msg, file_name, item_status | 아이템 실패 사유 | 배치 상세 + 음식 상세 |
| llm_call_cost.cost_krw | 원화 | 비용 표 병기 |
| food_review 집계(avg, count — `ReviewJpaRepository.kt:37,50`) | 사용자 반응 | 목록/상세 |
| scan_history.food_id count | 매칭 빈도 | 상세(우선순위) |
| ingredients 카탈로그 | 재료 편집 참조 | select 소스 + 읽기 화면 |
| bookmark / order_item.food_id count | 관심도 | 상세(추정) |

## 필요하지만 불가능한 조작

| 조작 | 현재 | 제안 |
|---|---|---|
| 화면에서 승인/반려(사유) | REST JWT 전용 | `POST /admin/foods/{id}/approve|reject` |
| 개별 재수집 | 조건 일괄만 | `POST /admin/foods/{id}/recollect` |
| 개별 이미지 재생성(READY 유지) | 강등 필요 | `POST /admin/foods/{id}/images/regenerate` |
| 이미지 직접 업로드 | 문자열 수기 | `POST /admin/foods/{id}/image` |
| 배치 즉시 회수 | cron 대기 | `POST /admin/foods/images/collect` |
| 배치 아이템 조회·재제출 | 카운트만 | `GET …/images/{batchId}`, `POST …/items/{id}/resubmit` |
| 콘텐츠 아웃박스 재발행/취소 | 없음 | `POST …/content-outboxes/{id}/requeue|cancel` |
| 벡터 실패 일괄 재처리 | 1건씩 | `POST …/vector-outboxes/retry-all` |
| 삭제 음식 조회·복구 | 불가 | `includeDeleted`, `restore` |
| 일괄 상태 전이/삭제 | 없음 | `POST /admin/foods/bulk` |
| 정렬·페이지·id/재료/번역 검색 | 고정 | `sort=&size=&ingredient=&q=<id>` |
| 재료 카탈로그 관리 | SQL/enum 배포 | `GET /admin/ingredients`(추정) |
| 지표 기간 | 7일 고정 | `?days=` |

## 우선순위 상위 10

1. 승인/반려 UI 부재 — 상태 드롭다운 백도어, 사유·횟수 유실
2. 맵기 `max="5"` — 6~10 음식 편집 저장 불가
3. 재료 JSON 무검증 — 앱 상세 500 유발
4. 편집 실패 시 입력 전량 유실
5. 상태 드롭다운 전이 규칙 우회 — 이미지·재료 없는 READY 노출
6. 개별 재수집 불가 — 과포함 SQS 발행
7. 개별 이미지 재생성/교체 불가 — 앱 노출 끊어야 함
8. 고착 SENT 아웃박스 식별 불가
9. 배치 아이템 실패 사유·수동 회수 없음
10. LLM 비용 과소 집계 미고지


---
---

# 회원·미노출 도메인 관리자 인터뷰 결과

> 감사 기준: `develop` 최신(커밋 cc8a7bf2), 마이그레이션 49개 누적 반영.

## 회원 화면 불편점 (/admin/members, /admin/members/{id})

- [P1] **검색·필터가 전혀 없다** — `AdminMemberPageController.kt:14`(`page` 만), `AdminMemberQueryService.kt:22-23`(`findAll` id desc), `MemberJpaRepository.kt` 검색 쿼리 없음. 시나리오: "닉네임 abc 문의"에 20건씩 넘기며 눈으로 찾음. 제안: `GET /api/admin/members?nickname=&email=&provider=&memberStatus=&onboarding=&createdFrom=&createdTo=&sort=`.
- [P2] **페이지 20 고정, 점프 불가** — `AdminMemberQueryService.kt:41`, `members.html:50-54`. 제안: `size`·`page` + 점프.
- [P1] **탈퇴 회원이 어디에도 없다** — `BaseEntity` `@SQLRestriction`, `Member.kt:152-154`(`withdraw` = providerUid 치환 + delete), 탈퇴 시각 컬럼 없음. 시나리오: 탈퇴자 리뷰 민원 시 누구였는지·언제 탈퇴했는지 못 봄. 제안: `includeWithdrawn=true`(native/@Query 우회) + `status`·`updatedAt` 노출.
- [P1] **소셜 계정 삭제 실패가 로그에만** — `AuthService.kt:93-94`. 시나리오: Firebase 삭제 실패 → DB 탈퇴 미진행 → 재문의, 운영자는 CloudWatch 를 뒤짐. 제안: 실패 기록 테이블/아웃박스 + `GET /api/admin/members/withdrawal-failures` + 재시도.
- [P1] **제재(SUSPENDED) 전이 수단 없음** — `MemberStatus.kt` 정의만, SUSPENDED 대입 0건, 위키 `member-auth.md`("운영자 수동 DB 조작 전용"). 정지자는 로그인 시 `DUPLICATE_SOCIAL_IDENTITY(409)` 라는 엉뚱한 코드. 제안: `PATCH /api/admin/members/{id}/status {memberStatus, reason}` + `suspended_at`·`suspend_reason`.
- [P2] **강제 탈퇴·닉네임/프로필 이미지 초기화 없음** — GET 2개뿐. 제안: `PATCH /api/admin/members/{id}/profile {nickname?, profileImageUrl?}`, `DELETE /api/admin/members/{id}`(소셜 삭제 선행 재사용).
- [P1] **상세에 `providerUid`·`email` 은 나오는데 활동 이력은 없음** — `member-detail.html:25`, `AdminMemberDetailView`(`AdminMemberQueryService.kt:77-92`). 제안: `recentScans[]`·`recentReviews[]`·`recentOrders[]`·`reportsReceived`·`reportsFiled`·`blocksCount` + `providerUid` 마스킹/토글.
- [P1] **스캔 크레딧(`scan_unlocked`) 미노출·조정 불가** — `Member.kt:68,157,160`, `V2026.08.19.16.20.00`. 시나리오: "리뷰 썼는데 스캔 3회 제한" 민원 대응 불가. 제안: `scanUnlocked`·`isScanAllowed` 노출 + `POST /api/admin/members/{id}/scan-unlock`.
- [P2] **랭킹 원장(`member_ranking_event`)·`unique_reviewed_food_count`·점수·다음 티어 미노출** — `AdminMemberQueryService.kt:110-111`, `Ranking.kt`. 제안: `ranking {score, tier, pointsToNext, …}` + `GET /api/admin/members/{id}/ranking-events`.
- [P2] **프로필 신규 컬럼 누락** — `diet_categories`(`V2026.08.19.02.12.21`)·`currency`(`V2026.08.11.13.24.22`). 제안: 상세에 추가.
- [P3] **`updated_at`·마지막 로그인 없음** — `last_login_at` 컬럼 부재. 제안: 최소 `updatedAt` 노출.
- [P3] **상세가 별도 페이지** — UI 선호(우측 패널)와 어긋남.

## 관리자에 없는 도메인

### 리뷰
- 시나리오: 신고 리뷰 숨김/삭제, 사진만 제거, 삭제 시 랭킹 원장 정합. `place_address_ko` 는 "백오피스가 채우는" 컬럼인데 화면 없음.
- DB: `food_review`(rating·content·image_refs·author_country_code·place_*·place_source·place_id·place_address_ko·serving_speed_rating·staff_kindness_rating·version), `review_like`, `member_ranking_event`.
- 조회: `GET /api/admin/reviews?foodId=&memberId=&minRating=&hasImage=&reported=true&createdFrom=`, 단건(작성자·음식·사진·위치·좋아요·신고 목록).
- 조작: `DELETE /api/admin/reviews/{id}`(현재 소유자 검증 `ReviewService.kt:285-289` 로 막힘 — 랭킹 차감 `ReviewService.kt:113-126` 재사용 필수), `PATCH …/images`(사진 제거), `PATCH …/place {addressKo}`.

### 주문
- 시나리오: 주문 사진 오연결·가격 이상 문의 시 주문·항목·사진·주소 확인.
- DB: `orders`(member_id·image_path UNIQUE·latitude/longitude·road_address), `order_item`(food_id·menu_name·quantity·price) — `V2026.08.20.16.53.09`. 리포는 본인 조회만.
- 조회: `GET /api/admin/orders?memberId=&foodId=&from=&to=`, 단건(항목·presigned 사진·좌표).
- 조작: `DELETE /api/admin/orders/{id}`(소프트).

### 커뮤니티 게시글/댓글
- 시나리오: 부적절 게시글·댓글 블라인드, 대댓글 트리. `Posting.kt:29-30` 은 관리자 조치를 전제하나 API 없음.
- DB: `community_post`(content·image_refs·food_ids·edited_at), `community_comment`(post_id·parent_id·edited_at). 블라인드 컬럼 없음.
- 조회: `GET /api/admin/community/posts?memberId=&keyword=&hasImage=`, `GET …/posts/{id}/comments`(삭제분 포함 트리).
- 조작: `DELETE …/posts/{id}`·`/comments/{id}`(소유자 검증 `CommunityService.kt:239-244, 179-184` 로 막힘). 복구 가능 블라인드는 `visibility` 컬럼 신설 필요.
- 부수 발견: `ReportTargetType` 은 `REVIEW` 하나 — 게시글·댓글은 신고조차 불가. `POST`/`COMMENT` 타입 선행.

### 신고
- 시나리오: 사유별 큐, 무혐의/삭제/제재 처리, 대상별 누적 건수. 현재 **쓰기만 되고 아무도 읽지 않음**.
- DB: `report`(reporter_member_id·target_type·target_id·reason(SPAM/ABUSE/FALSE_INFO/SEXUAL/OTHER)·detail) `V2026.08.01.05.16.32`. 처리 상태·처리자·시각·결과 컬럼 **없음**.
- 조회: `GET /api/admin/reports?status=PENDING&reason=&targetType=&sort=countDesc`, 단건(대상 콘텐츠 인라인 + 신고자).
- 조작: `PATCH /api/admin/reports/{id} {result: DISMISSED|CONTENT_DELETED|MEMBER_SUSPENDED, note}` — 선행: `handled_status`·`handled_by`·`handled_at`·`handle_note`.

### 스캔 이력
- 시나리오: 스캔 실패·비용 이상, 회원 남용, `food_id NULL` 추적.
- DB: `scan_history` — member_id·price·food_id(nullable)·status·created_at (`V2026.08.21.05.32.48` 이 image_path 등 drop).
- 조회: `GET /api/admin/scans?memberId=&foodId=&unmatched=true&from=&to=`, 회원 상세 임베드. 조작 없음.

### 북마크 [P3]
- `GET /api/admin/foods/{id}/bookmark-count`(삭제 확인 모달), 회원 상세 `bookmarkCount`.

### 앱 버전 [P1]
- REST 있으나 **화면 없음 + Bearer 토큰 획득 수단 없음**. 변경 이력 없음(단일 행 치환). 제안: 화면 + 변경 이력(누가·언제·이전값).

### 관리자 계정
- 현재 전부 SQL 직접(`specs/kb-245-admin-page-ui/quickstart.md:13-16`). `admin_account`(admin_id·admin_pwd) — 권한·마지막 로그인·변경 시각·잠금 없음.
- 조회: `GET /api/admin/accounts`. 조작: `POST /api/admin/accounts`, `PATCH /api/admin/accounts/me/password`, `DELETE …/{id}`. 선행: `last_login_at`·`password_changed_at`.

## 인증·보안·감사

- [P1] **SPA 용 JSON 로그인 없음** — 쿠키 `path=/admin`·HttpOnly(`AdminPageController.kt:62-68`), `/api/admin/*` 는 Authorization 헤더만(`WebConfig.kt:80-99`). 운영자는 REST(앱 버전·시드)를 못 씀. 제안: `POST /api/admin/auth/login → {accessToken, refreshToken}`.
- [P2] **세션 30분 만료·갱신 없음** — `access-ttl: 30m`, 쿠키 maxAge 없음. 검수 중 입력 유실. 제안: 관리자 TTL 8h 또는 refresh.
- [P2] **관리자 토큰 `memberId` 클레임이 admin_account.id** — 회원 id 공간과 충돌, MDC 오판(`AdminLoginService.kt:21`, `JwtAuthenticationFilter.kt:38`). 회원 API 는 리졸버가 ADMIN 거부하므로 "관리자 토큰이 회원 API 에 통한다"는 코드상 사실 아님 — 단 `@AuthMemberId` 미사용 엔드포인트는 뚫림. 제안: `sub` 타입 분리 + 필터 수준 거부.
- [P1] **감사 로그 부재** — 음식 편집·삭제·앱 버전 PUT 모두 이력 없음(`AdminAppVersionService.kt:19-27`). 제안: `admin_audit_log(admin_account_id, action, target_type, target_id, before_json, after_json, created_at)`.
- [P2] **로그인 실패 제한·잠금 없음** — `AdminLoginService.kt:17-22`, `/admin` 인터넷 노출. 제안: Redis 카운터 + 잠금 또는 IP allowlist.
- [P3] 쿠키 `secure(true)` 고정 — dev http 시 로그인 루프(추정).
- [P3] CORS `*` + credentials 가 `/api/admin` 포함 — SPA 도입 시 origin 좁힐 것.

## 데이터 정합

- "전체 N명" 기준 상이 — `members.html:7` findAll vs 대시보드 ACTIVE only. [P2]
- 상태 배지가 사실상 상수 — 전이 경로 없어 항상 ACTIVE, 탈퇴자는 안 보임 → 오독. [P2]
- 스캔/리뷰 수는 카운터 컬럼(잔액) — 실제 행 수·원장과 어긋나도 감지 불가, 정지 회원은 카운터 정지. [P2]
- `profile` JSON 레거시 잔존(`V2026.08.06.16.54.41`). [P3]
- 탈퇴 후 `email`·`nickname` 잔존 — `providerUid` 만 치환, 보존 정책 부재(추정 컴플라이언스). [P2]

## DB 에 있으나 노출되지 않는 정보

| 테이블.컬럼 | 의미 | 현재 |
|---|---|---|
| member.scan_unlocked | 무제한 스캔 해제 | 없음 |
| member.unique_reviewed_food_count | 랭킹 다양성 원천 | 없음 |
| member.diet_categories / currency | 식단·통화 | 없음 |
| member.updated_at / status=DELETED | 탈퇴 근사 시각·탈퇴자 | 없음 |
| member_ranking_event.* / member_block.* | 원장·차단 | 없음 |
| food_review.* / review_like.* | 리뷰 전체·좋아요 | 없음 |
| report.* | 신고 | 없음 |
| community_post.* / community_comment.* | 커뮤니티 | 없음 |
| orders.* / order_item.* | 주문 | 없음 |
| scan_history.* | 회원별 스캔 | 주간 합계만 |
| uploaded_image.* | 업로드 원장 | 없음 |
| app_version.* | 강제 업데이트 | REST 만 |
| admin_account.* | 운영자 계정 | 없음 |

## 우선순위 상위 10

1. 관리자 JSON 로그인/Bearer 발급 경로 부재
2. 신고 큐 부재 — 쓰기만 되고 처리 상태·조회·화면 전무
3. 회원 제재 전이 불가 + 정지자 에러 코드 오염(409)
4. 관리자 리뷰·게시글·댓글 삭제 경로 없음(소유자 검증에 잠김)
5. 감사 로그 부재
6. 회원 검색·필터 없음
7. 탈퇴 회원·시각 조회 불가 + 소셜 삭제 실패 로그에만
8. 회원 상세 활동 이력 없음, providerUid 상시 노출
9. `scan_unlocked` 미노출·수동 해제 불가
10. 앱 버전 화면·변경 이력 없음
