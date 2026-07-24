# Contract: 메뉴 목록 조회 API

`GET /api/v1/foods` — 검색어 없이 최신순 메뉴를 20개씩 keyset 커서로 조회한다.

## Request

| 요소 | 값 |
|------|-----|
| Method | `GET` |
| Path | `/api/v1/foods` (베이스 `ApiPaths.V1`) |
| Query `cursor` | 선택. 직전 페이지 `nextCursor`(마지막 항목 foodId). 미지정 시 첫 페이지. |
| Query `lang` | 선택. 응답 표시명 언어 코드. 미지정/빈/공백 → ko. 지원목록 밖 코드 → 400. |
| Body | 없음 |

지원 언어: `ko`(기본)·`zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es`.
페이지 크기는 서버 고정 20(클라이언트 지정 불가).

## Response 200 — 조회 성공

`BaseResponse<Page<MenuSummaryResponse>>` 봉투. `Page` 는 공유 커서 페이지 봉투(`com.meogo.app.api.common`)로 항목 리스트 필드명이 `items` 다(`BaseResponse.payload` 와의 중복을 피함) — 응답 경로는 `payload.items[]`.

```json
{
  "success": true,
  "payload": {
    "items": [
      {
        "foodId": 42,
        "name": "Doenjang Stew",
        "imageRef": "doenjang.png",
        "spiciness": 3,
        "overallRiskStatus": "DANGER"
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
| `payload.items[]` | array | 최신순(foodId 내림차순) ≤20개. 공유 'food summary'(`Page.items`). |
| `payload.items[].foodId` | number | 상세 조회로 이어질 안정 식별자(FR-005). |
| `payload.items[].name` | string | 요청 언어 표시명(번역부재 시 ko). |
| `payload.items[].imageRef` | string? | 대표 이미지 참조(없을 수 있음). |
| `payload.items[].spiciness` | number | 0~10. |
| `payload.items[].overallRiskStatus` | enum | `SAFE`·`CAUTION`·`DANGER`·`UNKNOWN`. 사용자 회피 ∩ 성분 위험도 최악값. |
| `payload.hasNext` | boolean | 다음 페이지 존재 여부. |
| `payload.nextCursor` | number? | 다음 요청에 넘길 커서(마지막 항목 foodId, **숫자**). `hasNext=false` 면 `null`. |

### 경계 케이스

- **빈 결과**: `payload.items:[]`, `hasNext:false`, `nextCursor:null` — 200 (오류 아님).
- **마지막 페이지**: 남은 ≤20개 반환, `hasNext:false`, `nextCursor:null`.
- **정확히 20 배수**: 꽉 찬 페이지 다음 요청은 빈 결과 페이지.

## Response 400 — 실패

`BaseResponse.fail(message)` 봉투(`success:false`, `payload:null`, `message`).

| 사유 | message(예) |
|------|-------------|
| 잘못된 커서(파싱 불가·음수) | `"커서 형식이 올바르지 않습니다"` |
| 지원 목록 밖 언어 코드 | 지원 언어 목록 안내(상세 API 와 동일 메시지 경로) |

```json
{ "success": false, "payload": null, "message": "커서 형식이 올바르지 않습니다" }
```

## 불변식 (테스트로 강제)

1. 연속 두 페이지 `items` 의 foodId 교집합은 항상 공집합(중복 없음, SC-002).
2. `nextCursor` 로 이어 조회하면 직전 페이지 최소 foodId 보다 작은 것만 온다(누락 없음, 단조 감소).
3. 스크롤 중 food 삽입/삭제가 있어도 1·2 유지(keyset 특성).
4. 모든 성공/실패 응답은 `BaseResponse` 봉투·`/api/v1` 경로(규약).
5. `overallRiskStatus` 는 상세 조회의 종합 위험도와 동일 의미(같은 회피 조달·카탈로그 필터).
