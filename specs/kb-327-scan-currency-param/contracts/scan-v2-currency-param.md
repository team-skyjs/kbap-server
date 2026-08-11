# Contract: 2.0 스캔 `currency` 요청 파라미터

**Endpoint**: `POST /api/scans` (`X-API-Version: 2.0` 이상) — 경로·버전 매핑 불변, 요청 파라미터만 추가

## 요청 변경분

| 파라미터 | 위치 | 타입 | 필수 | 의미 |
|----------|------|------|------|------|
| `currency` | query | string (ISO 4217, 대문자 정확 일치 — 예: `USD`·`JPY`) | **예** | 응답 통화 환산 정보의 기준 통화 — **단일 출처.** 회원 프로필 통화 설정은 읽지 않는다 |

기존 파라미터(`lang` query 필수, body `imagePath`)·인증(`Authorization` Bearer) 불변.

## 응답 (형태 불변 — 값 결정 규칙만 변경)

```json
{
  "success": true,
  "payload": {
    "degraded": false,
    "results": [ { "matched": true, "foodId": 7, "riskLevel": "SAFE", "name": "Kimchi Stew", "koreanName": "김치 찌개", "price": 9000, "similarFood": null } ],
    "currency": { "code": "JPY", "krwPerUnit": 8.8906 }
  }
}
```

`payload.currency` 결정 규칙:

| `currency` 파라미터 | 회원 프로필 통화 | `payload.currency` |
|---------------------|------------------|--------------------|
| `JPY` | `USD` (무관) | JPY 기준 — 프로필은 읽지 않음 |
| `USD` | 미설정 (무관) | USD 기준 |
| 없음 | 무관 | — 400 `COMMON-002`, 스캔 미실행 |
| `XXX` (미지원) | 무관 | — 400 `MEMBER-010`, 스캔 미실행 |

## 실패 계약

- `currency` 누락 → HTTP 400, `code == "COMMON-002"` (필수 파라미터 검증 — `lang` 누락과 동일 계열)
- 지원 목록에 없는 `currency` 값 → HTTP 400:

```json
{ "success": false, "code": "MEMBER-010", "message": "지원하지 않는 통화 코드입니다" }
```

- 클라이언트는 `code` 로만 분기한다(`message` 매칭 금지). `MEMBER-010` 은 프로필 통화 변경 API 와 같은 코드다.
- 두 검증 모두 스캔 실행 전이다 — 실패로 스캔 횟수가 늘거나 스캔 이력이 남지 않는다.

## 호환성

- **`currency` 필수화는 2.0 요청 계약 변경**이다 — 2.0 스캔 통화 정보(KB-323)가 아직 미출시 클라이언트 대상이라 릴리스 전 계약 확정으로 취급하고 버전 번호는 올리지 않는다(research R5). 2.0 을 쓰는 클라이언트는 `currency` 를 항상 보내야 한다.
- 1.0 스캔(`X-API-Version` 미전달/1.x, `/api/v1/scans`)은 요청·응답 모두 불변.
