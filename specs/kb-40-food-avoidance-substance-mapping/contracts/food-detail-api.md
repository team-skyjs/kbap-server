# Contract: GET /api/v1/foods/detail (동결 — 의미 재정의)

기존 계약을 **구조적으로 100% 동결**한다. 필드명·타입·중첩·정렬·에러 매핑 모두 불변. 데이터 원천만 재료 → 포함 기피 성분으로 바뀌고, 각 필드의 **의미**만 재정의된다.

## Request (불변)

```
GET /api/v1/foods/detail?menuName={한국어 음식명}&lang={선택 언어코드}
```

- `menuName` (required): blank 이면 400.
- `lang` (optional): 미지정(null/빈/공백) → ko 기본. 지원 언어이나 번역 부재 → ko 폴백. 지원 목록에 없는 코드 → 400 + 지원 목록 안내(헌법 V, 기존 동작 유지).

## Response 200 (구조 동결)

```json
{
  "success": true,
  "payload": {
    "name": "된장찌개",
    "imageRef": "doenjang.png",
    "briefDescription": "…",
    "detailedDescription": "…",
    "ingredients": [
      { "name": "대두", "iconRef": null, "inclusionPercent": 100, "riskStatus": "CAUTION" },
      { "name": "밀",  "iconRef": null, "inclusionPercent": 80,  "riskStatus": "SAFE" }
    ]
  },
  "message": null
}
```

### 필드 의미 재정의 (구조는 불변)

| JSON 키 | 타입 | 이전 의미 | **새 의미** |
|---------|------|-----------|-------------|
| `payload.ingredients` | array | 레시피 재료 목록 | **음식이 포함하는 기피 성분 목록** (포함 확률 내림차순 정렬) |
| `ingredients[].name` | string | 재료명(요청 언어) | 기피 성분 표시명(요청 언어, ko 폴백) |
| `ingredients[].iconRef` | string? | 재료 아이콘 | 성분 아이콘 — 현재 없음 → **항상 null** |
| `ingredients[].inclusionPercent` | int | 재료 함유 비율(0~100) | **포함 확률(1~100)** |
| `ingredients[].riskStatus` | string | mock 재료 위험도 | mock 성분 위험도 (SAFE/CAUTION/DANGER/UNKNOWN) |
| `name`,`imageRef`,`briefDescription`,`detailedDescription` | — | (불변) | (불변) |

- 포함 기피 성분이 없는 음식 → `ingredients: []`(빈 배열)로 정상 200.
- Swagger `@Schema` 의 description/example 문구는 새 의미로 갱신하되 필드 시그니처는 불변.

## Response 400 (불변)

- `BaseResponse.fail(message)` — menuName blank / 미지원 언어 코드 / 음식 없음(FoodException NOT_FOUND) 등 기존 매핑 유지.

## 계약 테스트 관점

- 응답 JSON 스키마(키 집합·타입·중첩) 스냅샷이 변경 전후 동일해야 한다(구조 회귀 없음).
- `inclusionPercent` 는 1~100 범위 정수만 출현(0·101·음수 부재).
- `iconRef` 는 null 허용 유지.
- 정렬: `inclusionPercent` 내림차순.
