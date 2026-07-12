# API Contract: GET /api/v1/home

홈 화면 진입 시 세 섹션을 한 번에 조회한다. 선택적 인증(비회원 허용).

## Request

```
GET /api/v1/home
Authorization: Bearer <accessToken>   # 선택 — 없으면 비회원으로 처리
```

- 쿼리 파라미터 없음. 언어는 서버가 결정한다(회원 = 프로필 `appLanguage`, 비회원·온보딩 미완료 = `en`).
- `Authorization` 헤더 없음/`Bearer ` 형식 아님 → 비회원.
- 헤더가 있으나 토큰이 위조·만료 → **401**(비회원 폴백 아님).

## Response 200 — 회원

```json
{
  "success": true,
  "payload": {
    "authenticated": true,
    "avoidedSubstances": [
      { "code": "EGG", "name": "Egg" },
      { "code": "MILK", "name": "Milk" }
    ],
    "popularFoods": [
      { "foodId": 12, "name": "Kimchi Stew", "koreanName": "김치찌개", "imageRef": "kimchi.png", "spiciness": 3, "overallRiskStatus": "SAFE" }
    ],
    "recentScans": [
      { "foodId": 30, "name": "Bibimbap", "koreanName": "비빔밥", "imageRef": "bibimbap.png", "spiciness": 1, "overallRiskStatus": "WARNING" }
    ]
  },
  "message": null
}
```

- `authenticated`: 요청자가 회원인지 여부. 비회원이면 false.
- `avoidedSubstances`: 회원 프로필의 기피 성분(코드 + 지역화 이름). 미설정이면 `[]`.
- `popularFoods`: 최대 5개(READY 음식 무작위). 위험도는 회원 기피 기준. READY 음식이 5개 미만이면 있는 만큼.
- `recentScans`: 최대 10개. 스캔 시각 내림차순·동일 메뉴 중복 제거·READY 매칭만. 이력 없으면 `[]`.
- **배열 필드는 null 을 내려주지 않는다** — 값이 없으면 빈 배열(클라이언트 null 분기 불필요).

## Response 200 — 비회원

```json
{
  "success": true,
  "payload": {
    "authenticated": false,
    "avoidedSubstances": [],
    "popularFoods": [
      { "foodId": 12, "name": "Kimchi Stew", "koreanName": "김치찌개", "imageRef": "kimchi.png", "spiciness": 3, "overallRiskStatus": "UNKNOWN" }
    ],
    "recentScans": []
  },
  "message": null
}
```

- 개인화 섹션은 빈 배열이고 `authenticated=false` 로 비회원임을 알린다(클라이언트는 이 플래그로 블러·가입 유도). 인기 음식만 영어로.

## Response 401 — 무효/만료 토큰

```json
{ "success": false, "payload": null, "message": "유효하지 않은 인증 토큰입니다" }
```

- 만료 시 message = "만료된 인증 토큰입니다".

## 응답 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `authenticated` | boolean | 요청자가 회원인지 여부(false=비회원 → 개인화 영역 블러) |
| `avoidedSubstances` | `AvoidedSubstanceView[]` | 회원 기피 성분(비회원·미설정은 `[]`) |
| `avoidedSubstances[].code` | string | 회피 성분 코드(`AvoidanceSubstanceCode`) |
| `avoidedSubstances[].name` | string | 지역화 이름 |
| `popularFoods` | `FoodSummaryView[]` | 인기 음식(항상, 최대 5) |
| `recentScans` | `FoodSummaryView[]` | 최근 스캔(비회원·이력없음은 `[]`, 최대 10) |
| `FoodSummaryView.foodId` | number | 음식 id |
| `FoodSummaryView.name` | string | 지역화 음식명 |
| `FoodSummaryView.koreanName` | string \| null | 한국어 원문(지역화명과 같으면 null) |
| `FoodSummaryView.imageRef` | string \| null | 이미지 참조 |
| `FoodSummaryView.spiciness` | number | 맵기 |
| `FoodSummaryView.overallRiskStatus` | enum | `SAFE`/`WARNING`/`DANGER`/`UNKNOWN` (회원 기피 기준) |

## 부수 효과 계약 — 스캔 이력 기록 (기존 `POST /api/v1/scans`)

- 요청/응답 스키마 **불변**. 단, 요청이 회원 토큰을 지녔고 항목이 READY 음식에 매칭되면 **스캔 이력이 회원별로 저장**된다(홈 최근 스캔의 원천). 비회원 스캔은 기록하지 않는다.
