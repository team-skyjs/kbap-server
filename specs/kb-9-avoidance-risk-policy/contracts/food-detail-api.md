# Contract: GET /api/v1/foods/detail (KB-9 갱신)

기존 음식 상세 조회 계약에 **종합 위험도 필드 추가**와 **성분별 위험도 실제값화**를 반영한다. 요청·언어 폴백·미등록 400·기존 필드는 **불변**.

## Request (불변)

`GET /api/v1/foods/detail`

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `menuName` | 예 | 한국어 메뉴명(앞뒤 공백 trim). 미수록/blank → 400 |
| `lang` | 아니오 | 응답 언어. 미지정/빈/공백 → ko. 지원 목록 밖 코드 → 400 |

## Response 200 (변경: `overallRiskStatus` 추가)

```json
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",
    "imageRef": "doenjang.png",
    "description": "A hearty Korean soybean paste stew.",
    "spiciness": 3,
    "overallRiskStatus": "DANGER",
    "ingredients": [
      { "name": "Soybean", "iconRef": null, "inclusionPercent": 100, "riskStatus": "DANGER" },
      { "name": "Wheat",   "iconRef": null, "inclusionPercent": 80,  "riskStatus": "DANGER" },
      { "name": "Clam",    "iconRef": null, "inclusionPercent": 50,  "riskStatus": "CAUTION" }
    ]
  },
  "message": null
}
```

### 필드 규약

| 필드 | 변경 | 규약 |
|---|---|---|
| `payload.overallRiskStatus` | **신규** | SAFE/CAUTION/DANGER/UNKNOWN. **사용자 회피 목록 ∩ 음식 성분**의 성분별 위험도 최악값. 대상 공집합/전부 SAFE → SAFE. (회피 목록은 현재 목 제공) |
| `payload.ingredients[].riskStatus` | **의미 변경** | 이제 목이 아니라 **포함 확률 기반 실제값**: `inclusionPercent<10`→SAFE, `10~59`→CAUTION, `≥60`→DANGER. 필드 구조·위치는 불변. 사용자 무관(음식 내재). |
| `payload.name·imageRef·description·spiciness` | 불변 | 현행 |
| `payload.ingredients[].{name,iconRef,inclusionPercent}` | 불변 | 현행. 목록은 회피 목록으로 필터링하지 않음(음식 포함 성분 전체). |

### 위 예시 산출 근거 (된장찌개, 목 회피 = {SOY,MILK,PEANUT,SHRIMP,EGG})
- 성분별: SOY 100→DANGER, WHEAT 80→DANGER, CLAM 50→CAUTION (사용자 무관).
- 종합: 회피 ∩ {SOY,WHEAT,CLAM} = {SOY} → SOY=DANGER → `overallRiskStatus=DANGER`.

## Response 400 (불변)

- `menuName` 누락/blank → "menuName은 필수입니다"
- 미수록 메뉴명 → "해당 음식 정보 없음" (**미등록 음식은 UNKNOWN 200 이 아니라 현행 400**, R7)
- 지원 목록 밖 언어 코드 → 지원 언어 목록 안내

## 하위호환

- `overallRiskStatus`는 **추가 필드** → 기존 클라이언트 무영향.
- `ingredients[].riskStatus`는 구조 동일, 값 의미만 mock→실제. 클라이언트는 이미 SAFE/CAUTION/DANGER/UNKNOWN 을 처리.
