# Contract: GET /api/home — X-API-Version 에 따른 popularFoods 선택 규칙

경로·요청 파라미터(`lang` 필수)·인증(선택 Bearer)·응답 스키마(`BaseResponse<HomeResponse>`)는 **기존 그대로**이고 핸들러도 하나다. 요청의 `X-API-Version` 만 보고 `popularFoods` 의 **내용 선택 규칙**을 가른다.

## `popularFoods`

| `X-API-Version` | `popularFoods` |
|------|----------------|
| 2.1 이상 | 설정 풀(`food-ids`) 중 READY 이고 존재하는 음식에서 **호출마다 무작위 최대 10건**. 삭제·미존재·비READY 는 풀에서 제외, 빈자리 미보충, 전부 제외면 `[]`. 폴백 없음 |
| 2.1 미만(1.0·1.1·2.0) | 최근 30일 조회 수 상위 10건(KB-714·KB-725 캐시) — 종전과 동일 |

- 각 항목의 `overallRiskStatus`·`bookmarked`·`review`·`name`(lang) 은 요청자 기준으로 계산된다(종전과 동일).
- `avoidedSubstances`·`mostReviewedFoods`·`recentScans`·`authenticated` 는 버전과 무관하게 같다.
- `2.1` 은 `WebConfig` 의 지원 버전에 등록돼 있다(매핑 선언 없이 `addSupportedVersions`). 미지원 버전(예: `2.2`)·헤더 누락은 기존대로 400 `COMMON-002`.

## 설정 (서버)

```yaml
kbap:
  home:
    marketing-popular:
      food-ids: ${HOME_MARKETING_POPULAR_FOOD_IDS:}    # 6,4,5,21,91,248,9,376,93,113,...
```

비어 있으면 2.1 의 `popularFoods` 는 `[]` 다(운영상 비우지 않는다).

## Swagger

오퍼레이션·그룹 추가 없음. 홈 오퍼레이션 설명에 2.1 이상 규칙을 적었다. `X-API-Version` 입력란은 기존 커스터마이저가 붙인다.

## 예시

설정 `food-ids: 1,2,3,999`, 음식 1 이 FAILED, 999 미존재:

```http
GET /api/home?lang=en
X-API-Version: 2.1
```

```json
{ "success": true, "payload": { "popularFoods": [ { "foodId": 3, ... }, { "foodId": 2, ... } ], ... } }
```

같은 요청에 `X-API-Version: 1.0` 이면 조회 수 집계 순서다.
