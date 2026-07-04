# Contract: `GET /api/v1/foods/detail` — 동결 (KB-48)

**상태**: 이 계약은 **동결(frozen)** 이다. KB-48 은 번역 저장 형태만 교체하며 요청/응답 스키마·값·에러 동작을 **변경하지 않는다**(FR-005, SC-001). 아래는 회귀 기준선(현행과 동일).

## Request

- Method/Path: `GET /api/v1/foods/detail`
- Query params:
  - `menuName` (string, required) — 한국어 원문 음식명(조회 매칭 키). blank 금지.
  - `lang` (string, optional) — 언어 코드. 미지정(null·빈·공백)이면 `ko`.

## Response — `BaseResponse<FoodDetailResponse>`

성공: `{ "success": true, "payload": FoodDetailResponse, "message": null }`

```jsonc
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",          // 요청 언어 음식명 (번역 부재/미지정/ko → 한국어 원문)
    "imageRef": "doenjang.png",       // nullable
    "description": "A hearty ...",     // 요청 언어 설명 (번역 부재/미지정/ko → 한국어 원문)
    "spiciness": 3,                    // 0~10
    "ingredients": [                   // 포함 기피성분(포함 확률 내림차순)
      {
        "name": "Soybean",            // 요청 언어 성분명(번역 부재 → 한국어) — avoidance 카탈로그에서 해석
        "iconRef": null,
        "inclusionPercent": 100,      // 1~100 (포함 확률)
        "riskStatus": "SAFE"          // SAFE | CAUTION | DANGER | UNKNOWN (mock)
      }
    ]
  },
  "message": null
}
```

### 필드 동결 표

| 필드 | 타입 | KB-48 이후 데이터 원천 | 변경 |
|------|------|------------------------|------|
| `name` | string | `food.korean_name` + **`food.name_translations` JSON**(구: `food_name_translation` 테이블) | 원천만 교체, 값 동일 |
| `imageRef` | string? | `food.image_ref` | 무변경 |
| `description` | string | `food.description` + **`food.description_translations` JSON**(구: `food_description_translation` 테이블) | 원천만 교체, 값 동일 |
| `spiciness` | int(0~10) | `food.spiciness` | 무변경 |
| `ingredients[].name` | string | `avoidance_substance.translations`(불변) | 무변경 |
| `ingredients[].iconRef` | string? | (미제공) | 무변경 |
| `ingredients[].inclusionPercent` | int(1~100) | `food_avoidance_substance.inclusion_percent` | 무변경 |
| `ingredients[].riskStatus` | string enum | mock marker | 무변경 |

## 에러 (동결)

| 상황 | 동작 |
|------|------|
| `menuName` blank | 400 (요청 검증) |
| 음식 미존재 | `FoodException(NOT_FOUND)` → 표준 에러 응답 |
| `lang` 미지원 코드(예: `fr`·`EN`·`ko-KR`) | `LanguageCode.from` → `UNSUPPORTED_LANGUAGE` 400 + 지원 언어 목록 안내(헌법 V, #23) |

## 언어 폴백 (동결 — 헌법 V)

1. `lang` 미지정 → `ko` 원문.
2. `lang` 지원 언어이나 해당 음식/성분 번역 부재 → `ko` 폴백.
3. `lang` 미지원 코드 → 400(조용한 폴백 없음).

> 검증: 이 계약은 기존 web 테스트(`FoodDetailControllerTest`·`FoodDetailLangTest`·`FoodDetailDescriptionTest`)가 회귀로 커버한다. KB-48 구현 후 이 테스트들이 수정 없이 통과해야 한다.
