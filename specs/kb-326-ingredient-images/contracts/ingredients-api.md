# API Contract: 재료 목록 공개 조회 (KB-326)

## GET /api/ingredients

온보딩 기피 재료 선택 화면용 재료 카탈로그 전체 목록. **인증 불필요**(JWT 보호 경로 미등록 — 토큰이 있든 무효든 무시된다). 버저닝은 기본(1.0) — `version` 매핑 없음.

### Request

| 파라미터 | 위치 | 필수 | 설명 |
|----------|------|------|------|
| lang | query | ✅ | 표시 언어 코드(`ko`·`en`·`ja`·`zh-Hans`·…). 빈 값/누락 → 400. 미지원 코드 → `en` 폴백(200) |

### Response 200 — `BaseResponse<IngredientListResponse>`

```json
{
  "success": true,
  "payload": {
    "ingredients": [
      { "code": "EGG", "name": "계란", "imageUrl": "https://cdn.example.com/images/webp/egg.webp" },
      { "code": "MILK", "name": "우유", "imageUrl": "https://cdn.example.com/images/webp/milk.webp" }
    ]
  },
  "message": null,
  "code": null
}
```

- `ingredients` 는 항상 카탈로그 전체(현재 81건), id 오름차순 고정.
- `name` 은 `lang` 언어 표시명, 해당 번역 부재 시 한국어 폴백.
- `imageUrl` 은 완성 공개 URL. 이미지 미매칭 재료는 `null` (목록 자체는 200).

### Response 400 — lang 누락/빈 값

`BaseResponse.fail` 봉투(기존 검증 실패 공통 처리) — 목록을 반환하지 않는다.

### 시나리오 매핑

| Spec | 계약 |
|------|------|
| US1-AS1 (비인증 조회) | Authorization 헤더 없이 200 + 81건 |
| US1-AS2 (인증자 동일 결과) | 유효/무효 토큰 동반에도 동일 200 |
| US1-AS3 (언어 표시) | `lang=en` → name 영어, 미지원 `lang=fr` → 영어 폴백 |
| US2-AS1 (전수 매칭) | 81건 전부 imageUrl 비-null |
| US2-AS2 (미매칭 허용) | image_path NULL 재료는 imageUrl null, 200 유지 |
