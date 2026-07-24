# Contract: GET /api/v1/foods/detail — 응답 계약 동결 + 장애 내성

본 태스크는 **API 계약을 변경하지 않는다.** 계약을 여기 명시하는 목적은 "삭제된 성분 skip" 이후에도 응답 구조·의미가 불변임을 못박기 위함이다.

## 요청 (기존, 불변)

`GET /api/v1/foods/detail?menuName={korean_name}&lang={langCode}`

## 응답 (기존, 불변) — `BaseResponse<FoodDetailResponse>`

```json
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",
    "imageRef": "doenjang.png",
    "description": "A hearty Korean soybean paste stew.",
    "spiciness": 3,
    "ingredients": [
      { "name": "Soybean", "iconRef": null, "inclusionPercent": 100, "riskStatus": "CAUTION" },
      { "name": "Wheat",   "iconRef": null, "inclusionPercent": 80,  "riskStatus": "SAFE" }
    ]
  },
  "message": null
}
```

- `ingredients[]` 필드 구조(`name`·`iconRef`·`inclusionPercent`·`riskStatus`)·정렬(포함 확률 내림차순): **동결**.

## 장애 내성 규칙 (본 태스크가 추가하는 행동 — 계약 표면 불변)

| # | 상황 | 변경 전 | 변경 후 (계약) |
|---|------|---------|----------------|
| C1 | 참조 성분 중 일부가 소프트 삭제됨 | HTTP 500 (조회 전체 실패) | **HTTP 200** + `ingredients[]` 에서 삭제 성분만 제외, 나머지는 동일 |
| C2 | 참조 성분이 전부 소프트 삭제됨 | HTTP 500 | **HTTP 200** + `ingredients: []` (빈 배열), 음식명·설명·맵기 정상 |
| C3 | 삭제된 성분 없음(정상) | HTTP 200 | **HTTP 200** — 응답 완전 동일(회귀 없음) |

- C1/C2 에서 skip 이 일어나면 서버는 **WARN 로그**(`foodId`, `substanceCode`)를 남긴다. 이는 응답 본문에 노출되지 않는 운영 관측 신호다.
- 음식 미존재는 본 계약과 무관하게 기존 동작(`success:false` / NOT_FOUND) 유지.

## 하위 계약 (application 유스케이스)

`GetFoodDetailUseCase.getDetail(GetFoodDetailInput): GetFoodDetailResult`

- 입력·반환 타입 시그니처 불변.
- 반환 `avoidanceSubstances: List<AvoidanceSubstanceView>` 는 카탈로그에 존재하는 성분만 포함(삭제 성분 제외).
- 카탈로그 부재 성분에 대해 예외를 **던지지 않는다**(기존 `IllegalStateException` 제거).
