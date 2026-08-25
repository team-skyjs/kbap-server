# Contract: FE 요구 반영분 — 대시보드 확장·회원 원문 노출·신규 도메인(신고·리뷰·커뮤니티·주문·스캔·앱 버전 이력·관리자 계정)

공통 규약은 `admin-auth-audit.md` 참조 (`X-API-Version: 1.0+`, `BaseResponse` 봉투, ADMIN JWT, 모든 쓰기는 `admin_audit_log`).
페이지 응답은 전부 `{items, page, size, totalCount, totalPages}` (page 1-base, size ≤ 200).

## 대시보드 확장 — GET /api/admin/dashboard?days

기존 응답에 추가:

| 필드 | 내용 |
|---|---|
| `contentOutbox.stuck[]` | `{outboxId, foodId, displayName, attempts, sentAt}` — SENT 후 `stuckHours`(3h) 경과, 오래된 순 최대 20 |
| `vectorOutbox.failures[]` | `{outboxId, foodId, displayName, attempts, lastError(≤200자), updatedAt}` 최근 20 |
| `pendingReviewPreview[]` / `pendingImagePreview[]` | `{id, koreanName, imageUrl?, updatedAt}` 최근 수정순 최대 14 |
| `generatingPreview[]` | `{outboxId, foodId, displayName, status(PENDING\|SENT), attempts}` 최근 14 |

`llmCost.daily[].costKrw` 는 호출 시점 환율로 저장된 값의 합이라 단일 환율 필드는 두지 않는다.

## 음식 목록 기본 크기

`GET /api/admin/foods` 의 `size` 기본값 200(최대 200).

## 회원 원문 노출 — GET /api/admin/members/{id}?reveal=true

- 기본: `email` 마스킹, `providerUid: null`, `revealed: false`.
- `reveal=true`: 원문 `email`·`providerUid`, `revealed: true`, 감사 로그 `MEMBER_PII_REVEAL`(target MEMBER).

## 신고 — /api/admin/reports

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/reports?status&reason&targetType&page&size` | `status`=PENDING\|HANDLED(처리 상태). 항목: `id, reporterMemberId, reporterNickname, target{type, id, authorMemberId?, contentPreview?(80자), exists, reportCount}, reason, detail, handleStatus, handleResult?, handledBy?, handledAt?, handleNote?, createdAt` |
| PATCH | `/api/admin/reports/{id}` `{result, note?}` | `result`=DISMISSED\|CONTENT_DELETED\|MEMBER_SUSPENDED. 응답 `{report, handledReportIds[]}` |

- `ReportTargetType` = REVIEW · POST · COMMENT (사용자 신고 API 도 POST/COMMENT 를 받는다).
- 같은 대상의 **미처리 신고는 함께 처리**된다(`handledReportIds`).
- CONTENT_DELETED: 리뷰 → 관리자 리뷰 삭제(랭킹 차감), 게시글/댓글 → 블라인드. 이미 삭제된 대상은 건너뛴다(`target.exists=false`).
- MEMBER_SUSPENDED: `note` 필수(정지 사유) → 400 COMMON-002. 대상이 삭제돼 작성자를 모르면 404 REPORT-003.
- 이미 처리됨 409 REPORT-004, 없는 신고 404 REPORT-005. 감사 `REPORT_HANDLE`.
- 스키마: `report.handle_status/handle_result/handled_by/handled_at/handle_note` (마이그레이션 `V2026.08.25.16.52.01`).

## 리뷰 — /api/admin/reviews

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/reviews?q&memberId&foodId&reported&hasImage&page&size` | `q` 숫자면 리뷰 id, 아니면 본문 contains. 항목: `id, memberId, memberNickname, foodId, foodDisplayName, rating, servingSpeedRating, staffKindnessRating, content, imageUrls[], placeName, authorCountryCode, likeCount, reportCount, createdAt, updatedAt` |
| DELETE | `/api/admin/reviews/{id}` | 소프트 삭제 + 작성자 리뷰 수/고유 음식 수 차감 + 랭킹 원장 REVIEW_DELETED. 응답 `{id, memberId, rankingAdjusted}` (작성자 비활성이면 false). 없는 리뷰 400 REVIEW-001. 감사 `REVIEW_DELETE` |
| PATCH | `/api/admin/reviews/{id}/images` | 사진만 제거(본문·별점 유지). 감사 `REVIEW_IMAGES_REMOVE` |

## 커뮤니티 — /api/admin/community

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/posts?q&memberId&page&size` | 항목: `id, memberId, memberNickname, content, imageUrls[], foodIds[], commentCount, reportCount, editedAt, createdAt` |
| GET | `/posts/{postId}/comments` | 삭제 포함 1depth 트리 `{postId, totalCount, comments[{id, memberId, memberNickname, content, deleted, reportCount, editedAt, createdAt, replies[]}]}`. 없는 글 400 COMMUNITY-001 |
| DELETE | `/posts/{postId}` | 블라인드(소프트 삭제, 복구 없음). 감사 `POST_DELETE` |
| DELETE | `/comments/{commentId}` | 블라인드 — 최상위면 대댓글도 함께. 감사 `COMMENT_DELETE` |

`visibility` 컬럼은 두지 않았다 — 블라인드 = 기존 소프트 삭제. 복구가 필요해지면 그때 컬럼을 추가한다.

## 주문 — /api/admin/orders

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `?memberId&from&to&page&size` | `from`/`to` = YYYY-MM-DD(양끝 포함). 항목: `id, memberId, memberNickname, roadAddress, itemCount, totalQuantity, totalPrice, scanImageUrl, createdAt` |
| GET | `/{id}` | `+ latitude, longitude, items[{id, foodId, foodDisplayName, foodImageUrl, menuName, quantity, price}]`. 없는 주문 404 ORDER-002 |
| DELETE | `/{id}` | 주문+항목 소프트 삭제 `{id, deleted, deletedItemCount}`. 감사 `ORDER_DELETE` |

메뉴판 사진은 공개 버킷 경로(`kbap.storage.public-base-url` + imagePath) — 별도 presigned 발급 없음.

## 스캔 — GET /api/admin/scans?memberId&unmatched&from&to&page&size

항목: `id, memberId, memberNickname, foodId?, foodDisplayName?, matched, price?, createdAt`. `unmatched=true` 미매칭만 / `false` 매칭만.

## 앱 버전 이력 — GET /api/admin/app-version/history?page&size

`PUT /api/admin/app-version` 이 감사 `APP_VERSION_UPDATE`(before/after 바뀐 필드만)를 남기고, 이력은 감사 로그 페이지(`AdminAuditLogPageResponse`: `adminAccountId, adminLoginId, action, before, after, createdAt`)로 준다. 별도 테이블 없음.

## 관리자 계정 — /api/admin/accounts

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/accounts` | `{items[{id, loginId, lastLoginAt, passwordChangedAt, createdAt}]}` |
| POST | `/api/admin/accounts` `{loginId, password}` | 아이디 4~50자 `[a-zA-Z0-9._-]`, 비밀번호 8자+. 중복 409 AUTH-011. 감사 `ADMIN_ACCOUNT_CREATE` |
| PATCH | `/api/admin/accounts/me/password` `{currentPassword, newPassword}` | 불일치 400 AUTH-012. 감사 `ADMIN_PASSWORD_CHANGE` |
| DELETE | `/api/admin/accounts/{id}` | 본인 400 AUTH-013, 없음 404 AUTH-014. 소프트 삭제 — refresh 거부, access 는 만료(1h)까지 유효. 감사 `ADMIN_ACCOUNT_DELETE` |

- 로그인 성공 시 `last_login_at` 갱신. 5회 실패 15분 잠금은 기존 로그인 API(AUTH-010).
- 스키마: `admin_account.last_login_at/password_changed_at` (`V2026.08.25.16.52.02`).
- 소프트 삭제된 계정의 `admin_id` 는 UNIQUE 에 남아 같은 아이디로 재생성할 수 없다(의도 — 감사 로그의 adminLoginId 보존).

## FE 요구 문서와 다른 점 (FE 가 맞출 것)

| FE 문서 | 실제 계약 | 이유 |
|---|---|---|
| 없는 리소스 404 `NOT_FOUND` | 도메인별 코드 유지 — 음식/회원/리뷰는 **400**(FOOD-001·MEMBER-003·REVIEW-001), 주문/신고/차단은 404 | 모바일 클라이언트가 기존 코드에 의존. 클라이언트는 `code` 로만 분기 |
| `STALE_VERSION`/`DUPLICATE_NAME`/`INVALID_STATE` | `COMMON-004`(version 불일치, payload.currentVersion) / `FOOD-007` / `FOOD-005`(payload.allowed[]) | 에러 코드는 `도메인-번호` 체계 |
| `errors: {field: message}` | `payload.errors[{field, code, message}]` 배열 | 한 필드에 복수 오류 표현 |
| 시드 `blockedByDeleted[]` | `blockedByDeletedNames[]` | 기존 계약 |
| 음식 상세 `outboxes[]`·`imageItems[]`·`vectorOutbox` 평면 | `history.{contentOutboxes, imageItems, vectorOutboxes, reviewSummary, scanMatchCount, bookmarkCount, auditLogs}` | 이력 묶음 |
| 회원 프로필 `{nicknameReset, profileImageReset}` | `{resetNickname, resetProfileImage}` | 기존 계약 |
| 콘텐츠 아웃박스 고착 24h | `stuckHours` 기본 3h(대시보드 응답에 명시, 목록 API 는 파라미터) | 운영 기준 |
| 관리자 TTL 8h | access 1h + refresh 7d(회전) | 탈취 시 노출 최소화 |
