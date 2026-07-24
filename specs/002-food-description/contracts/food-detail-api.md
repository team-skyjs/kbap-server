# Contract — 음식 상세 조회: 설명(간단·자세) 추가

`GET /api/v1/foods/detail?menuName={menuName}&lang={lang}`

> 002-food-description 변경분만 기술한다. 요청 규칙·매칭 키·재료·400 동작은 [001 계약](../../001-menu-scan-mock/contracts/food-detail-api.md)과 동일하며 **불변**. 본 기능은 응답 payload 에 `briefDescription`·`detailedDescription` **두 필드를 추가**하는 가산적 변경이다.

모든 응답은 `BaseResponse<T>`(`success`/`payload`/`message`)로 감싼다.

## Request (불변)

| 파라미터 | 위치 | 필수 | 규칙 |
|----------|------|------|------|
| `menuName` | query | 필수 | blank 불가. trim 후 seed `ko` 원문 음식명과 exact match |
| `lang` | query | 선택 | 지원: `ko`+9개. 미지정/미지원 → `ko` 폴백 |

## Response 200 — `BaseResponse.ok(FoodDetail)` (`lang=en` 예)

```jsonc
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",
    "briefDescription": "A hearty Korean soybean paste stew.",          // (신규) 요청 lang 간단 설명, 번역 없으면 ko
    "detailedDescription": "Doenjang-jjigae is a traditional ...",       // (신규) 요청 lang 자세한 설명, 번역 없으면 ko
    "imageRef": "https://.../doenjang.png",
    "ingredients": [
      { "name": "Manila clam",   "iconRef": "...", "inclusionPercent": 50,  "riskStatus": "CAUTION" },
      { "name": "Soybean paste", "iconRef": "...", "inclusionPercent": 100, "riskStatus": "SAFE" }
    ]
  },
  "message": null
}
```

### 신규 필드

| 필드 | 타입 | 규칙 |
|------|------|------|
| `briefDescription` | string (non-null) | 한두 문장 간단 설명. 요청 `lang` 번역, 해당 번역 없으면 `ko` 원문 폴백. 항상 채워짐 |
| `detailedDescription` | string (non-null) | 한 문단 자세한 설명. 요청 `lang` 번역, 해당 번역 없으면 `ko` 원문 폴백. 항상 채워짐 |

- 두 설명의 폴백은 **서로 그리고 `name`·재료명과 독립**이다. 예: `briefDescription` 만 `ru` 번역이 없으면 `briefDescription` 만 ko, `detailedDescription`·`name` 은 `ru` 유지.
- `ko` 또는 미지원/미지정 `lang` → 두 설명 모두 ko 원문.

## Response 400 (불변)

- 미수록 메뉴 → `{ success:false, message:"해당 음식 정보 없음" }`
- `menuName` 누락/blank → `{ success:false, message:"menuName은 필수입니다" }`
- `lang` 미지정/미지원은 400 아님 → ko 폴백(200).

## 계약/통합 테스트 (필수, 실패 먼저)

1. seed 음식 + `lang=en` → 200, `briefDescription`·`detailedDescription` 가 영어 텍스트로 응답에 포함.
2. seed 음식 + `lang` 미지정 → 200, 두 설명 모두 ko 원문.
3. seed 음식 + `lang=en` 이지만 `briefDescription` en 번역만 부재 → `briefDescription` ko, `detailedDescription` en (필드별 독립 폴백).
4. 미수록 메뉴 → 400 `"해당 음식 정보 없음"` (회귀).
5. `menuName` blank → 400 `"menuName은 필수입니다"` (회귀).
6. 두 설명 필드는 응답에서 **null 이 아니다**(항상 채워짐).
