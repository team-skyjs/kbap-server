# Contract: GET /api/home — X-API-Version 2.1+

경로·요청 파라미터(`lang` 필수)·인증(선택 Bearer)·응답 스키마(`BaseResponse<HomeResponse>`)는 **1.0 과 동일**하다. 달라지는 것은 `popularFoods` 의 **내용 선택 규칙** 하나다.

## 버전별 `popularFoods`

| 헤더 | 조건 | `popularFoods` |
|------|------|----------------|
| `X-API-Version: 1.0` (무버전 매핑) | 항상 | 최근 30일 조회 수 상위 10건(KB-714·KB-725 캐시). **변경 없음** |
| `X-API-Version: 2.1+` | 적용 기간 안 ∧ 목록 비어 있지 않음 | 설정 `food-ids` 순서대로, READY 가 아니거나 존재하지 않는 id 는 제외, 중복은 첫 등장만, 최대 10건. 제외로 생긴 빈자리는 채우지 않으며 전부 제외되면 `[]` |
| `X-API-Version: 2.1+` | 그 외(기간 전/후·미설정·빈 목록·시작≥종료) | 1.0 과 동일(조회 수 집계) |

- 각 항목의 `overallRiskStatus`·`bookmarked`·`review`·`name`(lang) 은 두 버전 모두 요청자 기준으로 계산된다.
- `avoidedSubstances`·`mostReviewedFoods`·`recentScans`·`authenticated` 는 두 버전이 같다.
- 헤더 누락·미지원 버전(예: `2.2` 가 선언되기 전) → 400 `COMMON-002`(기존 규칙).

## 설정 (서버)

```yaml
kbap:
  home:
    marketing-popular:
      starts-at: ${HOME_MARKETING_POPULAR_STARTS_AT:}   # 2026-11-01T00:00:00 (Asia/Seoul, 포함)
      ends-at:   ${HOME_MARKETING_POPULAR_ENDS_AT:}     # 2026-11-15T00:00:00 (Asia/Seoul, 제외)
      food-ids:  ${HOME_MARKETING_POPULAR_FOOD_IDS:}    # 6,4,5,21,91,248,9,376,93,113
```

기본(전부 빈 값)은 비활성이며 2.1+ 도 조회 수 집계를 내려준다.

## Swagger

- 그룹 드롭다운에 `X-API-Version 2.1` 추가. 2.1 그룹의 홈 오퍼레이션은 `operationId = homeMarketingPopular`.
- `/v3/api-docs`(병합 문서)에는 path+method 당 하나만 실리므로 2.1 계약은 그룹 문서로 본다(기존 규칙).

## 예시

설정 `food-ids: 3,1,999,2`, 음식 1 이 FAILED, 999 미존재, 기간 안:

```http
GET /api/home?lang=en
X-API-Version: 2.1
```

```json
{ "success": true, "payload": { "popularFoods": [ { "foodId": 3, ... }, { "foodId": 2, ... } ], ... } }
```

같은 시각 `X-API-Version: 1.0` 은 조회 수 집계 순서(예: `[1, 2, 3]` 중 READY 인 `[2, 3]` 는 집계 SQL 이 결정)를 내려준다.
