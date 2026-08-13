# Contract: v2 메뉴 스캔 — 항목별 기피성분 겹침

## Endpoint (기존 — 경로·메서드·버전 매핑 불변)

```
POST /api/scans
X-API-Version: 2.0
Authorization: Bearer <access token>   (필수 — 게스트 불가)
```

요청 계약 변경 없음.

## Response 변경 — `results[].avoidances` 필드 추가 (additive only)

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
        "similarFood": null,
        "avoidances": [                        // ← 신규
          { "code": "SHRIMP", "name": "Shrimp", "overlapped": true,  "riskLevel": "DANGER" },
          { "code": "PEANUT", "name": "Peanut", "overlapped": false, "riskLevel": null }
        ]
      },
      {
        "matched": false,
        "foodId": 12,
        "riskLevel": "UNKNOWN",
        "name": "옛날돈까스",
        "koreanName": "옛날돈까스",
        "price": 8000,
        "similarFood": { "foodId": 31, "name": "Pork Cutlet", "koreanName": "돈까스", "description": "...", "imageRef": null },
        "avoidances": []                       // ← 미매칭: 항상 빈 목록(판정 불가)
      }
    ],
    "currency": { "code": "USD", "krwPerUnit": 1416.0000 }
  }
}
```

## 필드 규약

| 필드 | 타입 | 규약 |
|------|------|------|
| `avoidances` | array \| null | 요청 회원의 기피성분 전체. **프로필 없는 회원(게스트·온보딩 미완료)은 전 항목 `null`** — 기피 정보 주체 부재. 프로필은 있으나 기피 미등록이면 `[]`. `matched=false`(degraded 포함)면 항상 `[]` — 겹침 판정 불가(항목 riskLevel UNKNOWN 과 일관). `similarFood` 존재 여부와 무관(유사 음식 성분으로 대체 판정하지 않음). |
| `avoidances[].code` | string | 성분 코드 식별자(`IngredientCode.name`, 예: `SHRIMP`). 순서는 코드 카탈로그 선언 순서로 고정(결정적). |
| `avoidances[].name` | string | 성분 표시명 — 요청 `lang` 으로 해석한 카탈로그 번역, 번역 부재 시 한국어 원문(음식 상세 성분명과 동일 규칙). |
| `avoidances[].overlapped` | boolean | 해당 음식 성분 데이터에 이 성분이 존재하면 `true`(포함 확률 임계값 없음). |
| `avoidances[].riskLevel` | string \| null | 겹친 성분의 경고 수준 — `SAFE`/`CAUTION`/`DANGER`(음식 상세의 성분별 `riskStatus` 와 동일 규칙: 포함 확률 10 미만 SAFE / 10~59 CAUTION / 60 이상 DANGER). `overlapped=false` 면 `null`. |

## 하위 호환

- 필드 **추가만** — 기존 필드의 이름·타입·의미 불변. 신규 필드를 모르는 클라이언트는 무시하면 됨.
- v1 스캔(`POST /api/scans` 무버전 매핑) 응답은 변경 없음.
- swagger: `ScanV2Response` 스키마에 신규 필드 설명 추가(그룹 문서 `/v3/api-docs/2.0` 에서 확인).
