# Data Model: KB-802 BE

스키마 변경·마이그레이션 없음.

## ReviewFoodResponse (응답 DTO, 필드 추가)

| 필드 | 타입 | 규칙 |
|---|---|---|
| foodId | Long | 기존 |
| name | String | 기존 — `displayName(lang)` |
| koreanName | String? | **신규** — `displayName(KO)`, `name` 과 같으면 null |
| imageUrl | String? | 기존 |

## ReviewDetailRequest (요청 파라미터)

| 필드 | 타입 | 규칙 |
|---|---|---|
| lang | String | `@NotBlank`. 미지원 값은 en 폴백(`LanguageCode.from`) |
