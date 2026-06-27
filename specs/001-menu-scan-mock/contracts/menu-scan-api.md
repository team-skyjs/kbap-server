# Contract — API 1: 메뉴 스캔 제출 + mock 판정

`POST /api/v1/menu-scans`

모든 응답은 `ApiResponse<T>`로 감싼다. `Content-Type: application/json`.

## Request Body

```jsonc
{
  "items": [
    {
      "itemId": 0,                         // 필수, Int, 요청 내 중복 불가
      "rawMenuName": "된장찌개",            // 필수, blank 불가
      "boundingBox": {                     // 필수, 정규화 비율(OCR 기준 이미지 대비)
        "x": 0.12,                         // ≥ 0
        "y": 0.34,                         // ≥ 0
        "width": 0.5,                      // > 0, x+width ≤ 1
        "height": 0.08                     // > 0, y+height ≤ 1
      }
    }
  ]                                        // 1..100개
}
```

### 제약
- `items`: 필수, 최소 1개, 최대 100개
- `items[].itemId`: 필수, 정수, 요청 내 중복 불가
- `items[].rawMenuName`: 필수, blank 불가
- `items[].boundingBox`: 필수, **정규화 비율 좌표**(클라이언트 OCR 기준 이미지 대비, 좌상단 0,0 / 우하단 1,1). 검증: `x≥0, y≥0, width>0, height>0, x+width≤1, y+height≤1`
- (OCR 신뢰도는 받지 않는다 — 클라이언트가 전송하지 않음)

## Response 200 — `ApiResponse.ok`

```jsonc
{
  "success": true,
  "data": {
    "scanId": 1024,
    "results": [
      { "itemId": 0, "riskLevel": "SAFE",    "reason": "mock: 안전으로 판정된 항목" },
      { "itemId": 1, "riskLevel": "CAUTION",  "reason": "mock: 주의 항목 — 매장 확인 필요" },
      { "itemId": 2, "riskLevel": "DANGER",   "reason": "mock: 위험 항목" },
      { "itemId": 3, "riskLevel": "UNKNOWN",  "reason": "mock: 판정 대상 식별 불가" }
    ]
  },
  "message": null
}
```

- `results`는 요청 `items`와 **itemId로 1:1 매칭**, 누락 없음.
- `riskLevel`은 요청 배열 index 기준 `index % 4` → `0 SAFE / 1 CAUTION / 2 DANGER / 3 UNKNOWN` 순환.

## Response 400 — `ApiResponse.fail`

```json
{ "success": false, "data": null, "message": "<무엇이 잘못됐는지 식별 가능한 메시지>" }
```

거부 케이스: `items` 빈 배열 · 100개 초과 · itemId 누락 · itemId 중복 · rawMenuName 누락/blank · boundingBox 누락 · boundingBox 좌표 검증 위반(`x<0`, `y<0`, `width≤0`, `height≤0`, `x+width>1`, `y+height>1`).

## 계약 테스트(필수, 실패 먼저)
- 4개 항목 제출 → 200, results 4개, itemId 1:1, 4단계 모두 포함(SC-001/003).
- 동일 메뉴명 2개(다른 itemId) → 각 itemId로 구분 매칭(SC-002).
- 5개 항목 → index 4가 SAFE로 재순환.
- 빈 items / 101개 / itemId 중복 / rawMenuName blank / boundingBox 누락 / width=0 / x=-1 / x+width>1(예: x=0.8,width=0.5) → 각각 400.
- 유효 제출 후 scanId·항목·boundingBox·결과 저장 확인(SC-006).
