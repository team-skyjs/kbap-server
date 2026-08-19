# API Contract: 스캔 2.0 응답 — similarFood 제거 후

## POST /api/scans (X-API-Version: 2.0) 응답

```jsonc
{
  "success": true,
  "payload": {
    "degraded": false,
    "results": [
      {
        "matched": true,
        "foodId": 7,
        "riskLevel": "DANGER",
        "name": "Kimchi Stew",
        "koreanName": "김치 찌개",
        "price": 9000,
        "avoidances": [ { "code": "SHRIMP", "name": "Shrimp", "overlapped": true, "riskLevel": "DANGER" } ]
      },
      {
        "matched": false,                  // DB miss — 1.0 원칙 그대로, 대체 없음
        "foodId": 152,
        "riskLevel": "UNKNOWN",
        "name": "할머니 손맛 갈비찜",        // 비전 정제 한국어명 그대로
        "koreanName": "할머니 손맛 갈비찜",
        "price": 15000,
        "avoidances": []
      }
    ],
    "currency": { "code": "USD", "krwPerUnit": 1416.0000 }
  }
}
```

**변경점**: 항목에서 `similarFood` 필드가 **제거**된다(키 자체 소멸). 그 외 필드·의미·순서 전부 불변.

**무변경**: 스캔 1.0 계약 전체 · v2 의 빈 추출 400(MENU_BOARD_NOT_DETECTED) · degraded 규칙 · avoidances/currency 규칙 · 이미지 필드 없음(원래 없음 — matched 이미지는 foodId 상세 조회로).

**버전**: 2.0 매핑 안에서 즉시 적용(버전 증가 없음, 클라이언트 조율 전제).
