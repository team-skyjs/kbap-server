# Contracts (CTO 계획 API 계약 표 그대로)

## Expo 푸시 data (서버 → 앱)
`{ "type": "FOOD_DISCOVERY", "foodId": 6, "notificationId": 98123 }` — Android channelId `news`.
ko 예: title `(광고) 🌟 삼계탕 드셔보셨어요?`, body `어린 닭 한 마리의 뱃속에 찹쌀, 인삼, 대추 등을 넣고 푹 끓인 보양식\n수신거부: 프로필 > 알림 설정`.

## GET /api/notifications (기존, X-API-Version 필수 1.1, X-Installation-Id)
```json
{ "success": true, "payload": [ { "id": 98123, "type": "FOOD_DISCOVERY", "orderId": null, "foodId": 6, "title": "(광고) 🌟 삼계탕 드셔보셨어요?", "body": "…\n수신거부: 프로필 > 알림 설정", "receivedAt": 1793750400000, "read": false } ], "message": null, "code": null }
```
다른 유형 행은 `foodId: null`. 에러: 400 COMMON-002 · 401.

## PATCH /api/notifications/{id}/read
응답 항목 스키마 동일(`foodId` 채워짐). 에러: 400 COMMON-002 · 401 · 404 NOTIFICATION-002.
