# Contract: 음식 탐색·수정·검수·전이·파이프라인 개입

공통 규약은 `admin-auth-audit.md` 참조. 모든 쓰기 조작은 감사 이력을 남긴다.

## GET /api/admin/foods

| 파라미터 | 의미 | 기본 |
|---|---|---|
| `q` | 숫자면 id 정확 일치, 아니면 표시명 contains | — |
| `ingredient` | 재료 코드(`IngredientCode`) 포함 | — |
| `translation` | 번역명 contains(9개 언어 전체) | — |
| `status` | `FoodContentStatus` | — |
| `failureKind` | `FoodContentFailureKind` | — |
| `includeDeleted` | true 면 삭제 행 포함 | false |
| `sort` | `id,desc`·`updatedAt,asc`·`updatedAt,desc`·`displayName,asc` | `id,desc` |
| `page` / `size` | 1-base / ≤200 | 1 / 50 |

```json
{ "items": [ {
    "id": 248, "displayName": "삼계탕", "koreanName": "삼계탕",
    "contentStatus": { "code": "READY", "label": "준비 완료" }, "contentFailureKind": null,
    "spiciness": 0, "hasImage": true, "imageUrl": "https://…/b7d….webp",
    "contentReviewAttempts": 0, "reviewCount": 12, "vectorSyncStatus": "COMPLETE",
    "deleted": false, "updatedAt": "2026-08-25T01:16:43"
  } ], "page": 1, "size": 50, "totalCount": 1200, "totalPages": 24 }
```
`vectorSyncStatus`: 최신 벡터 아웃박스 상태(`PENDING|COMPLETE|FAILED|NONE`).

## GET /api/admin/foods/{id}

삭제된 음식도 조회됨(`deleted: true`). 없으면 400 `FOOD-001`.

```json
{
  "id": 248, "koreanName": "삼계탕", "displayName": "삼계탕", "description": "…", "longDescription": "…",
  "spiciness": 0, "contentStatus": { "code": "PENDING_REVIEW", "label": "승인 대기" },
  "contentFailureKind": null, "contentReviewRejectionReason": null, "contentReviewAttempts": 1,
  "imageRef": "images/webp/food/….webp", "imageUrl": "https://…",
  "nameTranslations": { "en": "Ginseng Chicken Soup", "ja": "…", … },
  "descriptionTranslations": { … },
  "ingredients": [ { "code": "CHICKEN", "inclusionPercent": 100 } ],
  "allowedTransitions": [ "APPROVE", "REJECT" ],
  "version": 4, "deleted": false, "createdAt": "…", "updatedAt": "…",
  "contentOutboxes": [ { "id": 77, "status": "COMPLETE", "attempts": 1, "createdAt": "…", "sentAt": "…", "lastError": null } ],
  "imageItems": [ { "itemId": 5, "batchId": 2, "openaiBatchId": "batch_…", "status": "DONE", "fileName": "…", "errorMsg": null, "submittedAt": "…" } ],
  "vectorOutboxes": [ { "id": 9, "operation": "UPSERT", "status": "COMPLETE", "attempts": 1, "lastError": null } ],
  "reviewSummary": { "count": 12, "averageRating": 4.3 },
  "scanMatchCount": 57, "bookmarkCount": 8,
  "auditLogs": [ { "id": 10, "action": "FOOD_UPDATE", "adminLoginId": "ops", "createdAt": "…" } ]
}
```
`ingredients` 는 응답에서 camelCase(`inclusionPercent`); 요청도 동일(DB JSON 의 `inclusion_percent` 와는 매핑).

## PUT /api/admin/foods/{id}

```json
{ "version": 4, "koreanName": "삼계탕", "description": "…", "longDescription": "…", "spiciness": 0,
  "imageRef": "images/webp/food/….webp",
  "nameTranslations": { …9개 전부… }, "descriptionTranslations": { … },
  "ingredients": [ { "code": "CHICKEN", "inclusionPercent": 100 } ] }
```
- `contentStatus` 필드는 **받지 않는다**(있으면 400 COMMON-002).
- `version` 필수 — 불일치 409 `COMMON-004` `{ "currentVersion": 5 }`.
- 검증 실패 400 `FOOD-006` payload `{ "errors": [ { "field": "ingredients[0].code", "code": "UNKNOWN_INGREDIENT", "message": "…" } ] }`. 규칙: 이름 정규화 후 비어있지 않음·타 음식과 중복 없음(409 `FOOD-007`)·설명 1~255·긴 설명 ≤1000·맵기 0~10·번역 9개 언어 전부 비어있지 않음·재료 코드 카탈로그·비율 1~100(빈 배열 허용, 누락 400).
- 성공 200 → 상세 응답. READY 면 벡터 UPSERT 예약. 감사 `FOOD_UPDATE`(변경 필드만 before/after).

## 검수·전이

| 메서드·경로 | 본문 | 결과 |
|---|---|---|
| `POST /api/admin/foods/{id}/approve` | — | PENDING_REVIEW→READY, 벡터 UPSERT. 전제 미충족 409 `FOOD-005` `{ "allowed": [...], "reason": "NO_IMAGE" }` |
| `POST /api/admin/foods/{id}/reject` | `{ "reason": "…" }` 필수(빈 값 400) | PENDING_REVIEW→FAILED, attempts++ |
| `POST /api/admin/foods/{id}/transitions` | `{ "transition": "RESUBMIT|UNPUBLISH|APPROVE|REJECT", "reason"?: "…" }` | 허용 전이만. 아니면 409 `FOOD-005` |

응답은 `{ "id", "contentStatus", "allowedTransitions", "version" }`. 감사 `FOOD_APPROVE|FOOD_REJECT|FOOD_TRANSITION`.

## 삭제·복구·일괄

| 메서드·경로 | 본문 | 결과 |
|---|---|---|
| `DELETE /api/admin/foods/{id}` | — | 소프트 삭제 + 벡터 DELETE. 이미 삭제 200 멱등 |
| `POST /api/admin/foods/{id}/restore` | — | 활성화 + 벡터 UPSERT(READY 였으면). 동명 활성 음식 존재 409 `FOOD-007` |
| `POST /api/admin/foods/bulk` | `{ "action": "APPROVE|RECOLLECT|DELETE", "ids": [..] }` (≤500, 초과 400) | `{ "results": [ { "id": 1, "ok": true }, { "id": 2, "ok": false, "code": "FOOD-005", "message": "…" } ], "succeeded": 1, "failed": 1 }` — 건별 독립 트랜잭션 |

## 재수집

| 메서드·경로 | 결과 |
|---|---|
| `POST /api/admin/foods/{id}/recollect` | `{ "outboxId": 78, "created": true }` / 이미 PENDING 이면 `{ "outboxId": 77, "created": false }` (200) |
| `POST /api/admin/foods/recollect?q&status&ingredient&failureKind` | 조건 일괄(현행 유지, ≤500) `{ requested, created, skipped, exceeded, max }` |

## 이미지

| 메서드·경로 | 본문 | 결과 |
|---|---|---|
| `POST /api/admin/foods/{id}/image/regenerate` | — | 상태 무관 단건 배치 제출 `{ "batchId": 12, "itemId": 40 }`. 진행 중 아이템 있으면 409 `FOOD-009`. READY 유지 |
| `POST /api/admin/foods/{id}/image/upload-url` | `{ "contentType": "image/webp", "contentLength": 123456 }` | `{ uploadUrl, requiredHeaders, objectKey, expiresAt }` (형식 UPLOAD-001, 크기 UPLOAD-003) |
| `PUT /api/admin/foods/{id}/image` | `{ "objectKey": "…" }` | 저장소 존재·형식 확인(없으면 400 IMAGE-003) → `imageRef` 교체, READY 면 벡터 UPSERT. PENDING_IMAGE 였으면 PENDING_REVIEW 로 |

## 이미지 배치

| 메서드·경로 | 결과 |
|---|---|
| `GET /api/admin/foods/images?page&size` | `{ items: [ { id, batchStatus, openaiBatchId, model, promptVersion, submittedAt, collectedAt, pendingCount, doneCount, failedCount } ], … }` |
| `GET /api/admin/foods/images/candidates/count` | `{ "count": 17 }` — 다음 제출 대상(PENDING_IMAGE ∧ 진행 중 아이템 없음) |
| `POST /api/admin/foods/images` | (현행) `{ submittedBatchCount, submittedFoodCount }` |
| `GET /api/admin/foods/images/{batchId}` | `{ …배치…, items: [ { itemId, foodId, displayName, status, fileName, errorMsg } ] }` |
| `POST /api/admin/foods/images/collect` | 즉시 회수 `{ "collectedBatches": 2, "doneItems": 30, "failedItems": 1 }`. 락 점유 중 409 `FOOD-008` |
| `POST /api/admin/foods/images/items/resubmit` | `{ "itemIds": [..] }` → 대상 음식 단건 배치 `{ batchId, itemCount }` |

## 콘텐츠 아웃박스

| 메서드·경로 | 결과 |
|---|---|
| `GET /api/admin/foods/content-outboxes?status&stuckHours=3&foodId&page&size` | `{ items: [ { id, foodId, displayName, status, attempts, createdAt, sentAt, lastError, lastFailedAt, stuck: true } ], … }` — `stuck` = SENT ∧ sentAt < now−stuckHours |
| `POST …/content-outboxes/{id}/requeue` | SENT→PENDING(sentAt null). 상태 부적합 409 `FOOD-010` |
| `POST …/content-outboxes/{id}/cancel` | PENDING/SENT→CANCELED. 부적합 409 `FOOD-010` |

## 벡터 아웃박스

| 메서드·경로 | 결과 |
|---|---|
| `GET /api/admin/foods/vector-outboxes?status&page&size` | `{ items: [ { id, foodId, displayName, operation, status, attempts, lastError, updatedAt } ], … }` |
| `POST …/vector-outboxes/enqueue` | `{ "enqueued": 500, "remaining": 120 }` |
| `POST …/vector-outboxes/{id}/retry` | FAILED→PENDING. 부적합 409 `FOOD-010` |
| `POST …/vector-outboxes/retry-all-failed` | `{ "retried": 204 }` |

## 시드 · 재료 카탈로그

- `POST /api/admin/foods` `{ koreanNames: [...] }`(≤500) → `{ requested, created, skipped, "createdIds": [..], "skippedNames": [..], "blockedByDeletedNames": [..] }` (기존 3필드 유지 + 추가)
- `GET /api/admin/ingredients` → `{ items: [ { code, koreanName, translations: {…}, imageUrl } ] }` (81건, 정렬 code)
