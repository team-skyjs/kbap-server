# Contract: 2.0 스캔 응답 통화 환산 정보

**Endpoint**: `POST /api/scans` (기존 — 경로·요청 계약 불변)
**Version**: `X-API-Version: 2.0+` (기존 2.0 매핑에 응답 필드만 추가 — 새 버전 번호를 올리지 않는다: additive 변경은 기존 클라이언트를 깨지 않음)
**Auth**: JWT (기존과 동일)

## 요청

변경 없음.

## 응답 (변경분)

`BaseResponse<ScanV2Response>` 의 `payload` 에 `currency` 필드가 추가된다. 기존 필드는 형태·의미 모두 불변.

### 통화가 설정된 회원 (예: USD)

```json
{
  "success": true,
  "payload": {
    "degraded": false,
    "results": [
      {
        "matched": true,
        "foodId": 7,
        "riskLevel": "SAFE",
        "name": "Kimchi Stew",
        "koreanName": "김치 찌개",
        "price": 9000,
        "similarFood": null
      }
    ],
    "currency": {
      "code": "USD",
      "krwPerUnit": 1416.0000
    }
  }
}
```

- `currency.code`: 회원 프로필 통화의 ISO 4217 코드.
- `currency.krwPerUnit`: 해당 통화 1단위당 원화 금액(참고용 고정 스냅샷, 실시간 시세 아님).
- **클라이언트 환산식**: `표시 금액 = price ÷ krwPerUnit` — 통화별 소수점 자릿수·반올림은 클라이언트 소관 (예: 9000 ÷ 1416.0000 ≈ USD 6.36).
- `price` 가 null 인 항목은 환산 표시도 클라이언트가 생략한다.

### 통화가 설정되지 않은 회원

```json
{
  "success": true,
  "payload": {
    "degraded": false,
    "results": [ "...기존과 동일..." ],
    "currency": null
  }
}
```

스캔은 정상 성공하고 `currency` 만 null 이다.

### KRW 회원

```json
"currency": { "code": "KRW", "krwPerUnit": 1.0000 }
```

동일 형식 — 표시 생략 여부는 클라이언트가 결정.

## 실패 응답

변경 없음 — 통화 환산 정보는 성공 응답에만 실린다.

## 1.0 응답 (`X-API-Version` 미지정/1.x)

변경 없음 — `currency` 필드가 존재하지 않는다.
