# Contract: GET /api/home — popularFoods 선택 규칙

경로·요청 파라미터(`lang` 필수)·인증(선택 Bearer)·응답 스키마(`BaseResponse<HomeResponse>`)·`X-API-Version` 처리는 **모두 기존 그대로**다. 버전 분기·별도 오퍼레이션 없음. 달라지는 것은 `popularFoods` 의 **내용 선택 규칙** 하나다.

## `popularFoods`

| 조건 | `popularFoods` |
|------|----------------|
| 적용 기간 안 ∧ 목록 비어 있지 않음 | 설정 `food-ids` 순서대로, READY 가 아니거나 존재하지 않는 id 는 제외, 중복은 첫 등장만, 최대 10건. 제외로 생긴 빈자리는 채우지 않으며 전부 제외되면 `[]` |
| 그 외(기간 전/후·미설정·빈 목록·시작≥종료) | 최근 30일 조회 수 상위 10건(KB-714·KB-725 캐시) — 종전과 동일 |

- 각 항목의 `overallRiskStatus`·`bookmarked`·`review`·`name`(lang) 은 요청자 기준으로 계산된다(종전과 동일).
- `avoidedSubstances`·`mostReviewedFoods`·`recentScans`·`authenticated` 는 바뀌지 않는다.

## 설정 (서버)

```yaml
kbap:
  home:
    marketing-popular:
      starts-at: ${HOME_MARKETING_POPULAR_STARTS_AT:}   # 2026-11-01T00:00:00 (Asia/Seoul, 포함)
      ends-at:   ${HOME_MARKETING_POPULAR_ENDS_AT:}     # 2026-11-15T00:00:00 (Asia/Seoul, 제외)
      food-ids:  ${HOME_MARKETING_POPULAR_FOOD_IDS:}    # 6,4,5,21,91,248,9,376,93,113
```

기본(전부 빈 값)은 비활성이며 홈은 종전과 완전히 같다.

## 예시

설정 `food-ids: 3,1,999,2`, 음식 1 이 FAILED, 999 미존재, 기간 안:

```http
GET /api/home?lang=en
X-API-Version: 1.0
```

```json
{ "success": true, "payload": { "popularFoods": [ { "foodId": 3, ... }, { "foodId": 2, ... } ], ... } }
```
