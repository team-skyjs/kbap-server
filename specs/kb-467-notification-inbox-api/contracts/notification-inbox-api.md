# Contract: 회원 알림함 API

공통: `X-API-Version` 헤더 필수, `Authorization: Bearer <access>` 필수(게스트 401), 응답은 `BaseResponse` 봉투. 베이스 `ApiPaths.API + "/notifications"`.

## GET /api/notifications

최근 7일(조회 시각 기준 168시간) 알림 전부, 최신순. 파라미터 없음.

200:
```json
{ "success": true, "payload": [
  { "id": 130, "title": "리뷰에 도움됨이 눌렸어요", "body": "...", "receivedAt": 1788728400000, "read": false },
  { "id": 129, "title": "점검 안내", "body": "...", "receivedAt": 1788638400000, "read": true }
] }
```
없으면 `"payload": []`. `receivedAt` 은 epoch 밀리초(리뷰 목록·주문 `orderedAt` 과 동일 규약).

## PATCH /api/notifications/{id}/read

본인 알림 1건 읽음. 멱등, 취소 없음, 기간 제한 없음.

| 결과 | 상태 | 응답 |
|------|------|------|
| 성공(처음 또는 재시도) | 200 | payload = 갱신된 항목(`read: true`) |
| 타인 알림·부재·소프트삭제 | 404 | `code: "NOTIFICATION-002"` |
| 미인증 | 401 | AUTH |

## 보안 경계

- 두 경로 모두 JWT 필터 보호. `PUT /api/notifications/tokens` 는 필터 게스트 예외로 종전과 같이 토큰 없이 호출 가능(계약 불변). `/api/notifications/settings` 도 종전과 같이 보호.

## 만들지 않는 것

- 모두 읽기, 읽음 취소, 미읽음 수, 페이징(cursor·size), 종류 필터, 삭제.
