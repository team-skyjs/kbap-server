# Contract: 회원 탐색·조치

공통 규약은 `admin-auth-audit.md` 참조.

## GET /api/admin/members

| 파라미터 | 의미 | 기본 |
|---|---|---|
| `q` | 숫자면 id, 아니면 닉네임 contains |  |
| `email` | 이메일 contains(원문 검색 — 응답은 마스킹) |  |
| `provider` | GOOGLE / APPLE |  |
| `memberStatus` | ACTIVE / SUSPENDED |  |
| `onboardingCompleted` | true/false |  |
| `createdFrom` / `createdTo` | 가입일 범위(날짜) |  |
| `includeWithdrawn` | 탈퇴(소프트 삭제) 포함 | false |
| `sort` | `id,desc`·`createdAt,asc`·`nickname,asc` | `id,desc` |
| `page` / `size` | 1-base / ≤200 | 1 / 20 |

```json
{ "items": [ { "id": 17, "nickname": "abc", "email": "ab***@gmail.com", "provider": "GOOGLE",
               "memberStatus": "ACTIVE", "onboardingCompleted": true, "withdrawn": false,
               "createdAt": "…", "updatedAt": "…" } ],
  "page": 1, "size": 20, "totalCount": 3400, "totalPages": 170 }
```
탈퇴 회원: `withdrawn: true`, `withdrawnAt`(= updatedAt 근사) 포함.

## GET /api/admin/members/{id}

탈퇴 회원도 조회됨. 없으면 400 `MEMBER-003`.

```json
{
  "id": 17, "nickname": "abc", "email": "ab***@gmail.com", "provider": "GOOGLE",
  "memberStatus": "SUSPENDED", "suspendedAt": "…", "suspendReason": "욕설 반복",
  "withdrawn": false, "onboardingCompleted": true,
  "profileImageUrl": "https://…", "avoidanceSubstanceCodes": ["PEANUT"], "dietCategories": ["VEGAN"],
  "spicinessPreference": "MILD", "countryCode": "US", "currency": "USD",
  "scan": { "scanCount": 3, "scanUnlocked": false, "scanAllowed": false },
  "ranking": { "score": 74, "tier": "TASTER", "nextTier": "EXPLORER", "pointsToNext": 6,
               "reviewCount": 5, "uniqueReviewedFoodCount": 4 },
  "activity": {
    "reviewCount": 5, "orderCount": 2, "scanCount": 9, "bookmarkCount": 3,
    "reportsFiled": 1, "reportsReceived": 0, "blocksCount": 0,
    "recentScans":   [ { "scanId": 1, "foodId": 248, "displayName": "삼계탕", "createdAt": "…" } ],
    "recentReviews": [ { "reviewId": 1, "foodId": 248, "displayName": "삼계탕", "rating": 5, "createdAt": "…" } ],
    "recentOrders":  [ { "orderId": 1, "itemCount": 3, "createdAt": "…" } ]
  },
  "createdAt": "…", "updatedAt": "…"
}
```
- `providerUid` 는 응답에 없다. `email` 은 로컬파트 앞 2자만 노출.
- `activity.*Count` 는 실제 행 수(카운터 컬럼 `scan.scanCount`·`ranking.reviewCount` 와 별개 — 정합 확인용).
- 최근 목록은 각 5건.

## GET /api/admin/members/{id}/ranking-events?page&size

`{ items: [ { id, event: "REVIEW_CREATED|REVIEW_DELETED", reviewId, reviewCountDelta, uniqueFoodCountDelta, createdAt } ], … }`

## 조치

| 메서드·경로 | 본문 | 결과 |
|---|---|---|
| `PATCH /api/admin/members/{id}/status` | `{ "memberStatus": "SUSPENDED", "reason": "…" }`(정지 시 reason 필수) / `{ "memberStatus": "ACTIVE" }` | 상태·사유·시각 반영. 같은 상태로 재요청 200 멱등. 감사 `MEMBER_STATUS` |
| `PATCH /api/admin/members/{id}/profile` | `{ "resetNickname": true, "resetProfileImage": true }` (둘 다 false 면 400) | 닉네임 `사용자{id}`, 프로필 이미지 null. 감사 `MEMBER_PROFILE_RESET` |
| `POST /api/admin/members/{id}/scan-unlock` | — | `scanUnlocked=true`. 감사 `MEMBER_SCAN_UNLOCK` |
| `DELETE /api/admin/members/{id}` | — | 본인 탈퇴와 동일(외부 계정 삭제 선행). 외부 삭제 실패 500 `AUTH-007` + 감사 `MEMBER_WITHDRAW_FAILED`. 이미 탈퇴 200 멱등 |

## 정지 회원의 사용자 API

| 상황 | 응답 |
|---|---|
| `POST /api/auth/login`(소셜) — 정지 회원 | 403 `MEMBER-012`(기존 409 MEMBER-001 오염 제거) |
| 기존 액세스 토큰으로 회원 API 호출 | 403 `MEMBER-012` |
| 정지 해제 후 | 정상 |
