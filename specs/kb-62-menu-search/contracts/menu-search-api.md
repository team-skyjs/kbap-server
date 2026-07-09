# Contract: 검색어 메뉴 조회 API

`GET /api/v1/foods/search` — 검색어가 **한국어명 또는 요청 언어 번역명**에 포함되는 메뉴를 최신순 20개씩 keyset 커서로 조회한다.

## Request

| 요소 | 값 |
|------|-----|
| Method | `GET` |
| Path | `/api/v1/foods/search` (베이스 `ApiPaths.V1`) |
| Query `keyword` | **필수**. 검색어. trim 후 공백이면 400. 매칭 대상 = `korean_name` + 요청 언어 번역명. 대소문자 비구분 부분 일치(앞·중간·끝). |
| Query `cursor` | 선택. 직전 페이지 `nextCursor`(마지막 항목 foodId). 미지정 시 첫 페이지. 파싱 불가/음수 → 400. |
| Query `lang` | 선택. **매칭 대상 번역명 선택 + 응답 표시명 지역화** 양쪽에 쓰임. 미지정/빈/공백 → ko(=한국어명만 매칭). 지원목록 밖 코드 → 400. |
| Body | 없음 |

지원 언어: `ko`(기본)·`zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es`.
페이지 크기는 서버 고정 20(클라이언트 지정 불가).

**예**: `GET /api/v1/foods/search?keyword=bibim&lang=en` → 한국어명 또는 영어 번역명에 `bibim`(대소문자 무관)이 포함된 메뉴.

## Response 200 — 조회 성공

`BaseResponse<Page<MenuSummaryResponse>>` 봉투. `Page` 는 공유 커서 페이지 봉투(`com.meogo.app.api.common`), 항목 리스트 필드명 `items`(`payload.items[]`). 항목 스키마는 목록 조회(KB-63)와 **동일한 공유 'food summary'**.

```json
{
  "success": true,
  "payload": {
    "items": [
      {
        "foodId": 42,
        "name": "Bibimbap",
        "koreanName": "비빔밥",
        "imageRef": "bibimbap.png",
        "spiciness": 3,
        "overallRiskStatus": "CAUTION"
      }
    ],
    "hasNext": true,
    "nextCursor": 23
  },
  "message": null
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `payload.items[]` | array | 최신순(foodId 내림차순) ≤20개. 공유 'food summary'. |
| `payload.items[].foodId` | number | 상세 조회로 이어질 안정 식별자(FR-009). |
| `payload.items[].name` | string | 요청 언어 표시명(번역부재 시 ko). |
| `payload.items[].koreanName` | string? | 한국어 메뉴명. 표시명(`name`)과 다를 때만 채워짐(같으면 null). |
| `payload.items[].imageRef` | string? | 대표 이미지 참조(없을 수 있음). |
| `payload.items[].spiciness` | number | 0~10. |
| `payload.items[].overallRiskStatus` | enum | `SAFE`·`CAUTION`·`DANGER`·`UNKNOWN`. 사용자 회피 ∩ 성분 위험도 최악값(목록·상세와 동일 의미). **현재 `UNKNOWN` 은 이 경로에서 도달 불가**(아래 참고). |

> **`UNKNOWN` 도달 가능성 (현 구현 기준)**: `Food.overallRisk` → `RiskLevel.aggregate` 는 (a) 겹치는 회피 성분이 없으면 `SAFE`, (b) 있으면 `max(severity)` 를 낸다. `UNKNOWN` 은 개별 성분의 `riskLevel()` 이 `UNKNOWN` 일 때만 나오는데, `riskLevel()` 은 `fromInclusionProbability(1..100)` 이고 `FoodAvoidanceSubstance` 생성자가 그 범위를 강제하므로 **`SAFE`/`CAUTION`/`DANGER` 만 산출된다**. 따라서 `aggregate` 의 `UNKNOWN` 분기는 이 경로에서 실행되지 않는 방어 코드다. 클라이언트는 4값을 모두 처리하되, 테스트는 `UNKNOWN` 을 값으로 단언하지 않고 **enum 멤버십**으로만 검증한다(목록 API 와 동일).
| `payload.hasNext` | boolean | 다음 페이지 존재 여부. |
| `payload.nextCursor` | number? | 다음 요청에 넘길 커서(마지막 항목 foodId, **숫자**). `hasNext=false` 면 `null`. |

### 경계 케이스

- **결과 없음**: 매칭 0건 → `payload.items:[]`, `hasNext:false`, `nextCursor:null` — 200(오류 아님).
- **마지막 페이지**: 남은 ≤20개 반환, `hasNext:false`, `nextCursor:null`.
- **정확히 20 배수**: 꽉 찬 페이지 다음 요청은 빈 결과 페이지.
- **다음 페이지**: `nextCursor` 를 **같은 keyword(+lang)** 와 함께 넘겨야 이어진다.
- **소프트삭제**: 삭제된 메뉴는 결과에서 제외(FR-015).

## Response 400 — 실패

`BaseResponse.fail(message)` 봉투(`success:false`, `payload:null`, `message`).

| 사유 | message(예) |
|------|-------------|
| 빈/공백 검색어 | `"검색어를 입력해 주세요"` (`FoodErrorCode.BLANK_SEARCH_KEYWORD`) |
| 잘못된 커서(파싱 불가·음수) | `"커서 형식이 올바르지 않습니다"` (`FoodErrorCode.INVALID_CURSOR`) |
| 지원 목록 밖 언어 코드 | 지원 언어 목록 안내(상세·목록 API 와 동일 경로) |

```json
{ "success": false, "payload": null, "message": "검색어를 입력해 주세요" }
```

## 불변식 (테스트로 강제)

1. 결과의 모든 항목은 검색어를 `korean_name` 또는 요청 언어 번역명에 포함한다(대소문자 무관, SC-001).
2. `lang=en` 검색이 일본어 번역명 매칭을 반환하지 않는다(언어 분리, FR-004).
3. `lang` 미지정 검색은 한국어명 매칭만 반환한다(ko 폴백 → 한국어명만).
4. 연속 두 페이지 `items` 의 foodId 교집합은 항상 공집합(중복 없음, SC-002). `nextCursor` 로 이어 조회 시 직전 페이지 최소 foodId 보다 작은 것만 온다(누락 없음, 단조 감소).
5. 스크롤 중 food 삽입/삭제가 있어도 4 유지(keyset 특성).
6. 빈/공백 검색어는 400(빈 목록 200 아님, FR-011).
7. 모든 성공/실패 응답은 `BaseResponse` 봉투·`/api/v1` 경로(규약). `overallRiskStatus` 는 목록·상세와 동일 의미.
