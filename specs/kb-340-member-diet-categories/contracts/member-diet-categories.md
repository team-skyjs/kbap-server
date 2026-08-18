# API Contract: 회원 프로필 diet 복수 선택 (additive, 무버전)

모든 변경은 기존 엔드포인트에 `dietCategories` 필드 추가다 — 경로·버전·기존 필드 불변. 값은 diet 카테고리 코드(15종: `VEGAN`, `VEGETARIAN`, …, `SHELLFISH_ALLERGY`) 문자열 배열.

## 온보딩 — POST /api/members/me/onboarding (1.0·1.1+ 공통)

```jsonc
{
  "countryCode": "VN",
  "spicinessPreference": "MILD",
  "avoidanceSubstanceCodes": ["SHRIMP"],
  "dietCategories": ["VEGAN", "GLUTEN_FREE"]   // 추가 — 옵션, 누락 시 빈 목록
}
```

## 프로필 수정 — PATCH /api/members/me/profile (1.0·1.1+ 두 DTO 모두)

```jsonc
{ "dietCategories": ["MUSLIM"] }   // 추가 — 누락 = 기존 유지, [] = 전체 해제(기존 필드 규칙 동일)
```

## 내 프로필 조회 — GET /api/members/me/profile

```jsonc
{
  "success": true,
  "payload": {
    "nickname": "...",
    "avoidanceSubstanceCodes": ["SHRIMP"],     // 불변 — 직접 지정분만(현행 의미 유지)
    "dietCategories": ["VEGAN", "GLUTEN_FREE"], // 추가 — 미선택·기존 회원은 []
    "...": "기존 필드 불변"
  }
}
```

## 오류

- 미지원 diet 값(`"KETO"` 등) → **400 MEMBER-011**(신규 `INVALID_DIET_CATEGORY`). 중복 값은 오류 아님 — 집합 정규화.

## 무변경

- 위험도 판정·회피 재료 의미·`GET /api/ingredients/diets`(공개 매핑 조회)·`X-API-Version` 정책.
