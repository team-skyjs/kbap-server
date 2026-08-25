# Quickstart: KB-375 검증 시나리오

로컬(local 프로필) + Redis. `admin_account` 에 계정 1개(BCrypt) 선등록. 모든 `/api/admin` 호출에 `X-API-Version: 1.0` 헤더.

## Phase 0 — 자격·감사
1. `POST /api/admin/auth/login {id,password}` → 200 `{accessToken, refreshToken}`. 비밀번호 5회 오류 → 6번째 정답도 403 `AUTH-010`, 15분 후 성공.
2. 관리자 액세스 토큰으로 `GET /api/members/me` → 401 `AUTH-003`. 회원 토큰으로 `GET /api/admin/dashboard` → 403 `AUTH-008`.
3. 관리자 갱신 토큰으로 `POST /api/auth/refresh` → 401 `AUTH-005`; `POST /api/admin/auth/refresh` → 200, 같은 토큰 재사용 → 401.
4. 음식 1건 `PUT` 후 `GET /api/admin/audit-logs?targetType=FOOD&targetId=<id>` → `FOOD_UPDATE` 행에 before/after 변경 필드만.

## Phase 1 — 음식
5. `GET /api/admin/foods?ingredient=CHICKEN` → 닭 재료 음식만. `?q=248` → id 248 1건. `?failureKind=NOT_FOOD` / `?sort=updatedAt,asc&size=50`.
6. `GET /api/admin/foods/248` → `contentOutboxes/imageItems/vectorOutboxes/reviewSummary/allowedTransitions` 포함.
7. `PUT /api/admin/foods/248` — (a) `ingredients: [{code:"PEANUTS", inclusionPercent:100}]` → 400 `FOOD-006` errors[0].field=`ingredients[0].code`; (b) `inclusionPercent: 0` → 400; (c) 번역 `ja` 누락 → 400; (d) `version` 을 이전 값으로 → 409 `COMMON-004`; (e) 정상 → 200, 감사 기록.
8. PENDING_REVIEW 음식: `POST …/approve` → READY + 벡터 UPSERT PENDING 1건. 이미지 없는 PENDING_REVIEW: approve → 409 `FOOD-005` `reason: NO_IMAGE`. `POST …/reject {}` → 400, `{reason:"…"}` → FAILED, attempts+1.
9. FAILED 음식 `POST …/transitions {transition:"RESUBMIT"}` → PENDING_REVIEW(이미지 있음) / PENDING_IMAGE(없음), `contentFailureKind` null.
10. `POST /api/admin/foods/248/recollect` 두 번 → 첫 번째 `created:true`, 두 번째 `created:false` 같은 outboxId. `food_content_outbox` PENDING 1건만.
11. READY 음식 `POST …/image/regenerate` → 배치 1·아이템 1, 음식 상태 READY 유지. 재요청 → 409 `FOOD-009`. `POST …/image/upload-url` → presigned; 업로드 후 `PUT …/image {objectKey}` → `imageRef` 교체, 상태 유지.
12. `POST /api/admin/foods/images/collect` (SUBMITTED 배치 존재) → 회수 결과. 동시에 두 번 → 하나는 409 `FOOD-008`.
13. `GET /api/admin/foods/content-outboxes?stuckHours=0` → SENT 건이 `stuck:true`. `POST …/{id}/requeue` → PENDING, `sentAt` null. `POST …/{id}/cancel` → CANCELED; 랭체인 적재 콜백(`POST /api/admin/foods/contents` 그 outboxId) → 400.
14. FAILED 벡터 아웃박스 3건 → `POST …/vector-outboxes/retry-all-failed` → `{retried:3}`, 전부 PENDING attempts 0. `POST …/enqueue` → `{enqueued, remaining}`.
15. `DELETE /api/admin/foods/248` → `GET /api/admin/foods?q=248` 0건, `?includeDeleted=true` 1건 `deleted:true`. `POST …/restore` → 복귀. 동명 활성 음식 생성 후 restore → 409 `FOOD-007`.
16. `POST /api/admin/foods/bulk {action:"APPROVE", ids:[a,b,c]}` (b 는 이미지 없음) → results 에 b 만 `ok:false FOOD-005`, a·c READY.
17. `POST /api/admin/foods {koreanNames:["삼계탕","새음식","삭제된음식"]}` → `skippedNames:["삼계탕"]`, `blockedByDeletedNames:["삭제된음식"]`, `createdIds:[…]`.
18. `GET /api/admin/dashboard?days=30` → 30일 배열, `llmCost.scopeNote`·`costKrw`, `contentOutbox.stuckCount`. `?days=91` → 400.

## Phase 2 — 회원
19. `GET /api/admin/members?q=abc&provider=GOOGLE` → 해당 회원만. `?includeWithdrawn=true` → 탈퇴 회원 `withdrawn:true`.
20. `GET /api/admin/members/17` → `providerUid` 없음, `email` 마스킹, `activity.*`·`ranking.pointsToNext`·`scan.scanAllowed`.
21. `PATCH …/17/status {memberStatus:"SUSPENDED"}` → 400(reason 필수); `{…, reason:"욕설"}` → 200. 그 회원 소셜 로그인 → 403 `MEMBER-012`; 기존 토큰 `GET /api/members/me` → 403 `MEMBER-012`. `{memberStatus:"ACTIVE"}` → 로그인 정상.
22. `PATCH …/17/profile {resetNickname:true}` → 닉네임 `사용자17`. `POST …/17/scan-unlock` → `scan.scanAllowed:true`.
23. `DELETE /api/admin/members/17` → 200, `?includeWithdrawn=true` 에서 `withdrawn:true`; 재호출 200. 소셜 삭제 실패(모의) → 500 `AUTH-007` + 감사 `MEMBER_WITHDRAW_FAILED`.

## 구 화면 회귀
24. `/admin/login` 쿠키 로그인 → `/admin/foods`·`/admin/foods/list`·`/admin/foods/seed`·`/admin/foods/images`·`/admin/members` 전부 200. 편집 폼 저장(version hidden 포함) → 200; 다른 창에서 먼저 저장 후 저장 → `?error=stale`. 맵기 8 음식 편집 저장 성공. 상세 승인/반려 폼 동작. 구 화면 조작이 감사 이력에 남음.
