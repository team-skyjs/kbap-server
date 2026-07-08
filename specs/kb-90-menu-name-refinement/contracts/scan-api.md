# Contracts: 스캔 API 응답 변경

`POST /api/v1/menu-scans` — 요청 스키마 **변경 없음**(`items[].{itemId, rawMenuName, boundingBox}` 그대로). 응답 항목에 정제·매칭 결과를 추가한다. `BaseResponse<T>` 봉투·`/api/v1` 규약 유지.

## 응답 항목 필드 (SubmitMenuScanResponse.results[])

기존: `{ id, itemId, riskLevel, reason }`(riskLevel/reason 은 mock 유지). **추가**:

| 필드 | 타입 | 의미 |
|------|------|------|
| `matchStatus` | enum `MATCHED`/`PENDING`/`NOT_FOOD` | 정제·매칭 결과. 항상 3종 중 하나로 종결(정상 경로·폴백 무관) |
| `foodId` | Long? | `MATCHED` 일 때 매칭된 음식 id, 그 외 null |

- `itemId` 로 요청 항목과 1:1 매핑(FR-009, 기존 규약).
- **하위 호환**: 기존 필드 유지 + 신규 필드 추가(순수 확장). 클라이언트가 신규 필드를 무시해도 기존 동작 불변.

## matchStatus 별 의미 (클라이언트 처리 가이드)

- `MATCHED` + `foodId`: 아는 메뉴 — 그 음식으로 위험도/상세 연결 가능.
- `PENDING`: 처음 보는(또는 해석 지연) 메뉴 — "확인 중" 표시. 후속 조사 대기열 등록됨.
- `NOT_FOOD`: 비음식(원산지·가격·UI 텍스트 등) — 결과에서 제외/비표시.

## 예 (P2 완성 기준)

요청 항목: `[{itemId:0,"김치찌개 kimchi jjigae"}, {itemId:1,"김치찌게"}, {itemId:2,"원산지 중국"}, {itemId:3,"우주라면"(미등록)}]`

```json
{
  "success": true,
  "payload": {
    "scanId": 42,
    "results": [
      {"itemId": 0, "matchStatus": "MATCHED", "foodId": 7, "riskLevel": "…", "reason": "…"},
      {"itemId": 1, "matchStatus": "MATCHED", "foodId": 7, "riskLevel": "…", "reason": "…"},
      {"itemId": 2, "matchStatus": "NOT_FOOD", "foodId": null, "riskLevel": "…", "reason": "…"},
      {"itemId": 3, "matchStatus": "PENDING", "foodId": null, "riskLevel": "…", "reason": "…"}
    ]
  }
}
```

- 정상 경로: 0(혼합 로마자)·1(오탈자) 모두 LLM 이 `김치찌개` 로 정제 → 같은 음식(foodId 7) MATCHED. 2(`원산지 중국`)는 LLM NOT_FOOD, 3(`우주라면`)은 표준명 miss → PENDING+대기열.
- LLM 장애/미구성 시(폴백): 각 항목을 정규화 exact 매치 — 0(`김치찌개…`→키 `김치찌개`)은 hit=MATCHED로 살아있고, 1(`김치찌게`→키 `김치찌게`)·2·3 은 저장 음식에 없어 miss → 원문 PENDING+대기열. 아는 메뉴(0)는 무영향, 스캔은 200 성공(FR-006).
