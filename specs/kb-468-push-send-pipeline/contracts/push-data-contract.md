# Contract: 푸시 `data` 페이로드 (FE 고정 계약)

Expo 메시지 `data` 필드. FE 는 `type` 으로 딥링크를 분기한다. 구현 후 이 표를 KB-468 코멘트로 FE 에 공유한다.

```json
{ "type": "HELPFUL | SCAN_SUGGESTION | REVIEW_REMINDER | NOTICE | MEAL_TIME",
  "foodId": "123",
  "notificationId": 456 }
```

| 필드 | 타입 | 필수 | 채우는 곳 |
|------|------|------|-----------|
| `type` | string(enum name) | ✅ | 파이프라인(`PushRequest.type`) |
| `foodId` | string | 선택 | 트리거(`PushRequest.data`) — HELPFUL·REVIEW_REMINDER |
| `notificationId` | number | ✅ | 파이프라인(알림함 행 id — 앱이 읽음 처리 `PATCH /api/notifications/{id}/read` 에 쓴다) |

- `MEAL_TIME` 은 2026-09-11 신규(식사시간 트리거). `NUDGE` → `SCAN_SUGGESTION` 는 2026-09-07 개명.
- 크기 한도 4KB(Expo) — 현재 필드로는 100B 미만.
- **언어 갱신 책임(FE)**: 푸시·알림함 언어는 `notification_device.lang`(토큰 등록 시 보고값)만 본다. 앱 실행(포그라운드 진입)마다, 그리고 기기 언어 변경을 감지하면 `PUT /api/notifications/tokens` 를 현재 `lang` 으로 재호출한다. 재호출 전 발송분은 이전 언어, 이미 저장된 알림함 행은 소급되지 않는다.

# Contract: Expo Push API 호출 (어댑터 ↔ Expo)

- `POST {kbap.push.expo.base-url}/--/api/v2/push/send`, `Content-Type: application/json`, `Accept: application/json`, `Authorization: Bearer <token>`(토큰 설정 시만).
- 요청 본문: 배열, **≤ 100 항목**. 항목 `{ to, title, body, data, sound: "default", priority: "high", channelId: "default" }`.
- 응답 `{ "data": [ { "status": "ok", "id": "..." } | { "status": "error", "message": "...", "details": { "error": "DeviceNotRegistered" } } ] }` — 요청 순서와 동일.
- 어댑터 매핑: `ok` → `PushTicket(ok=true,id)`; `error` → `PushTicket(ok=false, error = details.error ?: message)`; 청크 HTTP/파싱 실패 → 그 청크 전부 `PushTicket(ok=false, error = "<ExceptionSimpleName>: <message>")`(255자 절단).

# Contract: 관리자 테스트 발송 API

`POST /api/admin/notifications/test-push` — 헤더 `X-API-Version: 1.0`, 관리자 Bearer.

요청 `{ "memberId": 35 }` → 200
```json
{ "success": true, "payload": { "sent": 1, "failed": 0 } }
```
- NOTICE 유형·고정 문구(`title="K-Bap"`, `body="테스트 알림입니다."`)·기기 언어로 렌더. 유효 기기 0대면 `{sent:0, failed:0}`.
- 회원 부재 400 `MEMBER-003`(기존 `MemberService.getMember`).
