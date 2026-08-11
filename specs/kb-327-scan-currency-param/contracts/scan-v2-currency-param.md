# Contract: 2.0 스캔 `currency` 요청 파라미터

**Endpoint**: `POST /api/scans` (`X-API-Version: 2.0` 이상) — 경로·버전 매핑 불변, 요청 파라미터만 추가

## 요청 변경분

| 파라미터 | 위치 | 타입 | 필수 | 의미 |
|----------|------|------|------|------|
| `currency` | query | string (ISO 4217, 대문자 정확 일치 — 예: `USD`·`JPY`) | 아니오 | 응답 통화 환산 정보의 기준 통화. **회원 프로필 설정보다 우선.** 미전달 시 프로필 통화 fallback, 프로필도 없으면 환산 정보 없음 |

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
| `JPY` | `USD` | JPY 기준 (프로필 무시) |
| `USD` | 미설정 | USD 기준 |
| 없음 | `USD` | USD 기준 (기존 동작) |
| 없음 | 미설정 | `null` (기존 동작) |
| `XXX` (미지원) | 무관 | — 아래 실패 응답, 스캔 미실행 |

## 실패 계약 (신규 발생 케이스)

지원 목록에 없는 `currency` 값 → HTTP 400:

```json
{ "success": false, "code": "MEMBER-010", "message": "지원하지 않는 통화 코드입니다" }
```

- 클라이언트는 `code` 로만 분기한다(`message` 매칭 금지). 프로필 통화 변경 API 와 같은 코드다.
- 검증은 스캔 실행 전이다 — 이 실패로 스캔 횟수가 늘거나 스캔 이력이 남지 않는다.

## 하위 호환

- 파라미터 미전달 요청의 응답은 도입 전과 바이트 수준 동일 규칙 — 기존 2.0 클라이언트 무영향.
- 1.0 스캔(`X-API-Version` 미전달/1.x, `/api/v1/scans`)은 요청·응답 모두 불변.
- 버전 번호 상향 없음(additive 변경 — research R5).
