# Contract: 관리자 자격·감사 이력·대시보드

공통: 경로 접두 `/api/admin`, 헤더 `X-API-Version: 1.0` 이상 필수, 응답 봉투 `{success, payload, message, code}`. 로그인·갱신을 제외한 전 엔드포인트는 `Authorization: Bearer <관리자 액세스 토큰>` — 없음/만료 401(AUTH-003/004), 회원 토큰 403(AUTH-008).

## POST /api/admin/auth/login

```json
{ "id": "ops", "password": "…" }
```
→ 200 `{ "accessToken": "…", "refreshToken": "…", "expiresIn": 3600 }`

| 상황 | 응답 |
|---|---|
| 아이디/비밀번호 불일치 | 401 `AUTH-009` (실패 횟수 +1) |
| 5회 실패 후(15분) | 403 `AUTH-010` — 잠금 중엔 정답도 거부, 잠금 시간 연장 없음 |
| 성공 | 실패 카운터 리셋, 감사 `ADMIN_LOGIN` |

## POST /api/admin/auth/refresh

`{ "refreshToken": "…" }` → 200 `{ accessToken, refreshToken }` (갱신 토큰 회전 — 이전 것 즉시 무효)

| 상황 | 응답 |
|---|---|
| 회원용(role=USER) 갱신 토큰 | 401 `AUTH-005` |
| 만료 | 401 `AUTH-006` (저장소에서 제거) |
| 재사용(이미 소비) | 401 `AUTH-005` |

회원 `POST /api/auth/refresh` 에 관리자 갱신 토큰을 주면 401 `AUTH-005`.

## POST /api/admin/auth/logout

`{ "refreshToken": "…" }` → 200, 갱신 토큰 폐기(비어 있거나 파싱 불가여도 200).

## 관리자 토큰으로 회원 API

`GET /api/members/me` 등 `@AuthMemberId` 엔드포인트 → 401 `AUTH-003`(기존 동작 유지). 로그 MDC 는 `adminId` 키.

## GET /api/admin/audit-logs

쿼리: `targetType?`, `targetId?`, `adminAccountId?`, `action?`, `from?`, `to?`(ISO-8601), `page=1`, `size=50`(≤200)

```json
{ "items": [ {
    "id": 10, "adminAccountId": 1, "adminLoginId": "ops",
    "action": "FOOD_UPDATE", "targetType": "FOOD", "targetId": 248,
    "before": { "description": "…" }, "after": { "description": "…" },
    "note": null, "createdAt": "2026-08-25T14:10:00"
  } ],
  "page": 1, "size": 50, "totalCount": 1234, "totalPages": 25 }
```
`before`/`after` 는 변경된 필드만. 일괄 작업 행은 `targetId: null`, `note: "ids=[1,2,3]"`.

## GET /api/admin/dashboard?days=7

`days` 1..90 (기본 7, 범위 밖 400 COMMON-002)

```json
{
  "foods": { "total": 1200, "byStatus": [ { "code": "FAILED", "label": "확인 필요", "count": 12 }, … ], "readyRatio": 91.3 },
  "contentOutbox": { "pending": 3, "sent": 8, "complete": 900, "canceled": 2, "stuckCount": 5, "stuckHours": 3 },
  "vectorOutbox": { "pending": 1, "complete": 880, "failed": 4, "unenqueued": 7 },
  "metrics": {
    "days": 7, "totalActiveMembers": 3400,
    "dailyScans": [ { "date": "2026-08-19", "count": 120 }, … ],
    "dailyNewFoods": [ … ],
    "llmCost": {
      "scopeNote": "스캔 비전 + 이미지 생성 성공분만 집계(임베딩·실패분 제외)",
      "daily": [ { "date": "2026-08-19", "callCount": 40, "costUsd": 1.23, "costKrw": 1845.00,
                   "models": [ { "modelName": "gpt-5.6-luna", "callCount": 40, "inputTokens": 1, "outputTokens": 1, "costUsd": 1.23, "costKrw": 1845.00 } ] } ]
    }
  }
}
```
`heightPct`·`dayLabel` 없음(클라이언트 계산). 상태 `label` 은 `FoodContentStatus.displayName` 단일 출처.
