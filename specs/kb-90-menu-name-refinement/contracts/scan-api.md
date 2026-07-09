# Contracts: 스캔 API

`POST /api/v1/menu-scans?lang=en` — `BaseResponse<T>` 봉투·`/api/v1` 규약을 따른다.

> **develop 대비 breaking change.** 요청에서 `boundingBox`가, 응답에서 `scanId`·`results[].id`·`reason`이 빠졌고, `degraded`·`matchStatus`·`foodId`·`name`·`koreanName`이 추가됐다. 응답 개수도 요청과 달라질 수 있다.

## 요청

```json
{ "items": [
    { "itemId": 10, "rawMenuName": "김치찌개 kimchi jjigae" },
    { "itemId": 20, "rawMenuName": "원산지 : 중국" }
]}
```

| 필드 | 타입 | 제약 |
|------|------|------|
| `items` | array | 1~100개 |
| `items[].itemId` | int | 필수. **요청 안에서 유일**(중복 시 400). 순서가 아니라 클라이언트의 매칭 키 |
| `items[].rawMenuName` | string | 필수, blank 불가 |
| `lang` (쿼리) | string | 선택. 응답 `name` 의 언어. 미지정/빈/공백이면 `ko`, 지원 목록 밖이면 400 |

바운딩 박스는 서버가 쓰지 않으므로 받지 않는다.

## 응답

```json
{ "success": true,
  "payload": {
    "degraded": false,
    "results": [
      { "itemId": 10, "matchStatus": "MATCHED",   "foodId": 2,    "riskLevel": "DANGER",  "name": "Kimchi Stew", "koreanName": "김치찌개" },
      { "itemId": 50, "matchStatus": "UNMATCHED", "foodId": 11,   "riskLevel": "UNKNOWN", "name": "우주라면",     "koreanName": "우주라면" },
      { "itemId": 60, "matchStatus": "UNMATCHED", "foodId": null, "riskLevel": "UNKNOWN", "name": null,          "koreanName": null }
    ]
  },
  "message": null }
```

| 필드 | 타입 | 의미 |
|------|------|------|
| `degraded` | boolean | `true`면 정제 서비스 미적용(미구성·실패). 메뉴가 아닌 텍스트가 `results`에 섞여 있을 수 있다 |
| `results[].itemId` | int | 요청 `itemId`와 짝. **인덱스가 아니라 이 키로 맞춘다** |
| `results[].matchStatus` | enum | `MATCHED`(조회 가능한 음식) / `UNMATCHED`(조사 대기 — 위험도 판정 불가) |
| `results[].foodId` | long? | 음식 PK. `UNMATCHED`여도 조사 대기로 등록된 음식이면 값이 있고, 판정 자체가 불가하면 `null` |
| `results[].riskLevel` | enum | `SAFE`/`CAUTION`/`DANGER`(MATCHED) · `UNKNOWN`(UNMATCHED) |
| `results[].name` | string? | `lang` 으로 지역화한 표시명. 번역이 없으면 한국어 폴백(조사 대기 음식은 번역이 없어 항상 한국어). `foodId=null` 이면 null |
| `results[].koreanName` | string? | 언어 무관 한국어 메뉴명. `foodId=null` 이면 null |

`name`·`koreanName` 은 **서버가 아는 음식일 때만** 채워진다. 폴백 중 DB 에도 없는 항목은 서버가 아는 이름이 없으므로 둘 다 `null` 이고, 클라이언트는 자기가 보낸 `rawMenuName` 을 쓴다.

### ⚠️ `results` 개수는 요청보다 적을 수 있다

메뉴가 아닌 항목(원산지·가격·UI 문구·한글 없는 텍스트)은 **결과에서 제외**된다. 클라이언트는 반드시 `itemId`로 짝을 맞추고, 응답에 없는 `itemId`는 "메뉴 아님"으로 처리한다.

## 상태 조합

| 상황 | `matchStatus` | `foodId` | `riskLevel` | `name` | 응답 포함 |
|------|---------------|----------|-------------|--------|-----------|
| 완성 음식과 매칭 | `MATCHED` | 있음 | 산출값 | 지역화(번역 없으면 ko) | ✅ |
| 미완성 음식과 매칭 | `UNMATCHED` | 있음 | `UNKNOWN` | 한국어 | ✅ |
| 미등록 → 미완성 등록 | `UNMATCHED` | 있음(신규) | `UNKNOWN` | 한국어 | ✅ |
| 폴백 중 판정 불가 | `UNMATCHED` | `null` | `UNKNOWN` | `null` | ✅ (`degraded=true`) |
| 메뉴가 아님 | — | — | — | — | ❌ 제외 |

## 실측 예 (Upstage solar-pro)

요청 5항목 → 응답 3항목:

| itemId | 원문 | 결과 |
|--------|------|------|
| 10 | `김치찌개 kimchi jjigae` | `MATCHED` foodId=2 `DANGER` |
| 20 | `원산지 : 중국` | 제외(비음식) |
| 30 | `된장찌게 8,000` | `MATCHED` foodId=1 `DANGER` — 오탈자 교정 |
| 40 | `MacBook Air F9` | 제외(한글 0자, LLM 호출 안 함) |
| 50 | `우주라면` | `UNMATCHED` foodId=11 `UNKNOWN` — 미완성 등록 |

## 오류

| 상태 | 사유 |
|------|------|
| 400 | `items` 비었거나 101개 초과, `itemId` 누락·중복, `rawMenuName` blank, 지원하지 않는 `lang` |
