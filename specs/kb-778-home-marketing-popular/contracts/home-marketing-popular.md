# Contract: GET /api/home — X-API-Version 에 따른 popularFoods 선택 규칙

경로·요청 파라미터(`lang` 필수)·인증(선택 Bearer)·응답 스키마(`BaseResponse<HomeResponse>`)는 **기존 그대로**이고 핸들러도 하나다. 요청의 `X-API-Version` 만 보고 `popularFoods` 의 **내용 선택 규칙**을 가른다.

## `popularFoods`

| `X-API-Version` | `popularFoods` |
|------|----------------|
| 1.2 이상 | `food_marketing_popular` 풀 중 READY 이고 존재하는 음식에서 **호출마다 무작위 최대 10건**. 삭제·미존재·비READY 는 풀에서 제외, 빈자리 미보충, 전부 제외면 `[]`. 폴백 없음 |
| 1.2 미만(1.0·1.1) | 최근 30일 조회 수 상위 10건(KB-714·KB-725 캐시) — 종전과 동일 |

- 각 항목의 `overallRiskStatus`·`bookmarked`·`review`·`name`(lang) 은 요청자 기준으로 계산된다(종전과 동일).
- `avoidedSubstances`·`mostReviewedFoods`·`recentScans`·`authenticated` 는 버전과 무관하게 같다.
- `1.2` 은 `WebConfig` 의 지원 버전에 등록돼 있다(매핑 선언 없이 `addSupportedVersions`). 미지원 버전(예: `2.2`)·헤더 누락은 기존대로 400 `COMMON-002`.

## 풀 관리 (DB)

```sql
INSERT INTO food_marketing_popular (food_id, status, created_at, updated_at)
VALUES (6, 'ACTIVE', NOW(6), NOW(6)), (4, 'ACTIVE', NOW(6), NOW(6));   -- 추가
UPDATE food_marketing_popular SET status = 'DELETED' WHERE food_id = 4; -- 제외
```

`food_id` 유니크·food FK. 다음 요청부터 즉시 반영(캐시 없음). 비어 있으면 1.2 의 `popularFoods` 는 `[]` 다(운영상 비우지 않는다).

## Swagger

오퍼레이션·그룹 추가 없음. 홈 오퍼레이션 설명에 1.2 이상 규칙을 적었다. `X-API-Version` 입력란은 기존 커스터마이저가 붙인다.

## 예시

풀 `{1, 2, 3}`, 음식 1 이 FAILED:

```http
GET /api/home?lang=en
X-API-Version: 1.2
```

```json
{ "success": true, "payload": { "popularFoods": [ { "foodId": 3, ... }, { "foodId": 2, ... } ], ... } }
```

같은 요청에 `X-API-Version: 1.0` 이면 조회 수 집계 순서다.
