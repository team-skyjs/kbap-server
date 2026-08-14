# API Contract: GET /api/ingredients/diets

## 요청

```http
GET /api/ingredients/diets?lang=en HTTP/1.1
Authorization: Bearer <access-token>
X-API-Version: 1.0
```

| 항목 | 값 |
|------|-----|
| 경로 | 기존 `IngredientController`(`ApiPaths.API + "/ingredients"`)의 `@GetMapping("/diets")` (버전 매핑 없는 기본 핸들러) |
| 인증 | JWT 필수 — `WebConfig` JWT 필터 `addUrlPatterns` 에 `/api/ingredients/diets` 등록 (기존 `/api/ingredients` 는 공개 유지) |
| `X-API-Version` | 필수(전역 규약) — 누락·미지원이면 400 COMMON-002 |
| `lang` (query, 필수) | 표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지원 코드 → en 폴백, 번역 부재 → ko 폴백. 누락·공백 → 400 |

## 성공 응답 (200)

```json
{
  "success": true,
  "payload": {
    "diets": [
      {
        "code": "VEGAN",
        "name": "비건",
        "ingredients": [
          { "id": 1, "name": "Egg" },
          { "id": 2, "name": "Milk" }
        ]
      },
      {
        "code": "GLUTEN_FREE",
        "name": "글루텐 프리",
        "ingredients": [
          { "id": 26, "name": "Wheat" },
          { "id": 28, "name": "Barley" },
          { "id": 29, "name": "Rye" },
          { "id": 30, "name": "Oat" }
        ]
      }
    ]
  },
  "message": null,
  "code": null
}
```

- `diets` 는 15종 전체, `DietCategory` 선언 순서(기획 표 순서).
- `ingredients` 는 재료 id 오름차순. `name` 은 요청 `lang` 표시명.
- 카테고리 `name` 은 한국어 고정(다국어 범위 밖 — 클라이언트는 `code` 분기).

## 실패 응답

| 상황 | HTTP | code | 비고 |
|------|------|------|------|
| `lang` 누락·빈 값 | 400 | COMMON-002 (validation) | `@field:NotBlank` |
| `X-API-Version` 누락·미지원 | 400 | COMMON-002 | 전역 규약 |
| 토큰 없음·만료 | 401 | AUTH-* | access 만료 AUTH-004 → refresh 흐름 |

## swagger

- 기존 `IngredientApi` 인터페이스에 diets 오퍼레이션의 `@Operation`·`@ApiResponses`·`@SecurityRequirement` 추가 — 문서 애너테이션만. Spring 매핑·바인딩 애너테이션은 `IngredientController` 에.
