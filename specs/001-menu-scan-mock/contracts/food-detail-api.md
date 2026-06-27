# Contract — API 2: 음식 상세 조회 (다국어)

`GET /api/v1/foods/detail?menuName={menuName}&lang={lang}`

모든 응답은 `ApiResponse<T>`로 감싼다.

## Request

| 파라미터 | 위치 | 필수 | 규칙 |
|----------|------|------|------|
| `menuName` | query | 필수 | blank 불가. **trim 후** seed의 `ko` 원문 음식명과 **exact match** |
| `lang` | query | 선택 | 지원: `ko`+9개(`zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es`). **미지정/미지원 → `ko` 폴백** |

예: `GET /api/v1/foods/detail?menuName=된장찌개&lang=en`

> 매칭 키(`menuName`=ko 원문)와 응답 콘텐츠 언어(`lang`)는 **분리**된다. 음식명·재료명은 `ko` 원문 + 9개 대상 언어로 사전 번역돼 저장되며(헌법 V·ADR-0003), 응답은 `lang` 한 언어만 내려준다.

## Response 200 — `ApiResponse.ok(FoodDetail)`  (`lang=en` 예)

```jsonc
{
  "success": true,
  "data": {
    "name": "Doenjang Stew",                 // 요청 언어(lang) 음식명, 미지원 시 ko
    "imageRef": "https://.../doenjang.png",
    "ingredients": [
      { "name": "Manila clam",     "iconRef": "...", "inclusionPercent": 50,  "riskStatus": "CAUTION" },
      { "name": "Soybean paste",   "iconRef": "...", "inclusionPercent": 100, "riskStatus": "SAFE" },
      { "name": "Tofu",            "iconRef": "...", "inclusionPercent": 90,  "riskStatus": "SAFE" },
      { "name": "Korean zucchini", "iconRef": "...", "inclusionPercent": 85,  "riskStatus": "SAFE" },
      { "name": "Beef",            "iconRef": "...", "inclusionPercent": 40,  "riskStatus": "SAFE" }
    ]
  },
  "message": null
}
```

- `name`(음식·재료): **요청 `lang` 한 언어**. 해당 언어 번역이 없으면 그 항목만 `ko` 폴백.
- `inclusionPercent`: 저장된 값(0~100).
- `riskStatus`: **mock**(첫 재료 CAUTION, 나머지 SAFE). 저장 안 함, application이 부여.
- 사장님 안내 **완성 문장은 응답에 없음** — 클라이언트가 위 구조화 값 + 로컬 템플릿으로 조합.

## Response 404 — `ApiResponse.fail`

메뉴명이 seed에 없을 때(리소스 없음): `{ "success": false, "data": null, "message": "해당 음식 정보 없음" }`

## Response 400 — `ApiResponse.fail`

`menuName` 누락/blank: `{ "success": false, "data": null, "message": "menuName은 필수입니다" }`

> `lang` 미지정/미지원은 **400이 아니라** `ko` 폴백(200)이다.

## 계약 테스트(필수, 실패 먼저)
- seed 메뉴명("된장찌개") + `lang=en` → 200, 영어 음식명·재료명 + 포함%·riskStatus, 첫 재료 CAUTION·나머지 SAFE.
- 동일 메뉴명 `lang=ja` → 일본어 번역본 반환.
- `lang` 미지정 / `lang=xx`(미지원) → `ko` 폴백 200("된장찌개" 등 ko 값).
- 앞뒤 공백 포함("  된장찌개  ") → trim 후 200.
- 없는 메뉴명("없는메뉴") → 404 + "해당 음식 정보 없음".
- `menuName` 미제공 / 빈 문자열 → 400 + "menuName은 필수입니다".
- 재료 없는 음식(seed) → ingredients 빈 배열 + 200.
