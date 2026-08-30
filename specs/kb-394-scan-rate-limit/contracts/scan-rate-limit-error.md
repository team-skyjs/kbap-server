# Contract: 스캔 rate-limit 에러 응답 (SCAN-008)

대상: `POST /api/scans`(무버전 v1, `X-API-Version: 2.0` v2 — 티켓). 벤더 요청 한도 초과로 스캔이 실패했을 때.

## 응답

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "success": false,
  "code": "SCAN-008",
  "message": "일시적으로 요청이 많습니다. 잠시 후 다시 시도해 주세요",
  "payload": { "retryAfterSeconds": 20 }
}
```

- `payload.retryAfterSeconds`: 벤더가 재시도 권고 시각(`Retry-After`/`Retry-After-Ms`)을 준 경우만 존재(초, 올림 없이 정수). 없으면 `"payload": null`.
- 클라이언트는 `code` 로만 분기한다. SCAN-002(사진 다시 찍기 유도)·SCAN-006(서버 장애 재시도 모달)과 배타. 권장 UX: 재시도 모달 + `retryAfterSeconds` 가 있으면 그 시간만큼 버튼 비활성/카운트다운.
- 스캔 횟수(v2 예약·v1 카운트)는 차감되지 않는다 — 같은 티켓으로 재시도 불가(1회용)이므로 v2 는 티켓 재발급 후 재시도.

## 변경 없는 것

| 코드 | 상태 | 의미 | 변경 |
|---|---|---|---|
| SCAN-002 | 503 | 메뉴판 인식 실패(벤더 응답은 왔으나 결과 불가·4xx·파싱 실패) | 없음 — 단 429·5xx·IO 는 더 이상 여기로 오지 않음 |
| SCAN-003 | 400 | 메뉴판 미검출 | 없음 |
| SCAN-006 | 503 | 스캔 서버 일시 장애(5xx·타임아웃·연결 실패, 예산 내 재시도 소진) | 코드·메시지 동일, 도달 경로만 SDK 예외 기준으로 복구 |

## OpenAPI

`ScanApi`·`ScanV2Api` 503 응답 설명에 `SCAN-008 — 벤더 요청 한도 초과, 잠시 후 재시도(payload.retryAfterSeconds)` 추가, `errorCodes` 에 `ErrorCode.SCAN_RATE_LIMITED`.
