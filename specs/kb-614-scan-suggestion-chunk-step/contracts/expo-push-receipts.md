# Contract: Expo Push Receipts (소비 계약)

출처: https://docs.expo.dev/push-notifications/sending-notifications/ (2026-09-21 확인)

## 요청

```http
POST {kbap.push.expo.base-url}/--/api/v2/push/getReceipts
Content-Type: application/json
Authorization: Bearer <access-token>      # 토큰이 설정된 경우에만

{ "ids": ["<ticket-id>", ...] }           # 최대 1000개. 초과 시 PUSH_TOO_MANY_RECEIPTS
```

## 응답

```json
{
  "data": {
    "<ticket-id>": { "status": "ok" },
    "<ticket-id>": { "status": "error", "message": "...", "details": { "error": "DeviceNotRegistered" } }
  }
}
```

- 요청한 id 가 `data` 에 **없을 수 있다** — 아직 발급 전이거나 24시간이 지나 지워진 것이다. 오류가 아니다.
- `status: ok` 는 FCM·APNs 가 받았다는 뜻이며 기기 수신을 보장하지 않는다.
- 발송 15분 뒤 조회를 권고한다. 영수증은 24시간 뒤 삭제된다.

## 포트 매핑 (`PushReceiptFetcher.fetch`)

| Expo | `PushReceipt` |
|------|---------------|
| `status = ok` | `ok = true` |
| `status = error` + `details.error` | `ok = false`, `errorCode = details.error`, `message` |
| `status = error`, `details.error` 없음 | `ok = false`, `errorCode = null`, `message` |
| `data` 에 id 없음 | 반환 맵에 키 없음 |
| HTTP 4xx·5xx·네트워크 오류 | 예외 전파(호출자가 그 묶음을 건너뛴다) |

## 오류 코드와 우리의 처리

| `details.error` | 뜻 | 처리 |
|-----------------|----|------|
| `DeviceNotRegistered` | 기기가 더는 받을 수 없음 | FAILED + 토큰 무효화, 재전송 안 함 |
| `MessageRateExceeded` | 그 기기에 너무 자주 보냄 | FAILED + 재전송 조건 판정 |
| `MessageTooBig` | 4096바이트 초과 | FAILED + 경고, 재전송 안 함 |
| `MismatchSenderId` | FCM 자격 증명 불일치 | FAILED + 경고, 재전송 안 함 |
| `InvalidCredentials` | 푸시 자격 증명 무효 | FAILED + 경고, 재전송 안 함 |
| (없음) | 분류되지 않은 실패 | FAILED + 재전송 조건 판정 |
| (모르는 새 코드) | — | FAILED + 경고, 재전송 안 함 |
