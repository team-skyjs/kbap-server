# Contracts: 스캔 API

`POST /api/v1/menu-scans` — `BaseResponse<T>` 봉투·`/api/v1` 규약을 따른다.

> **develop 대비 breaking change.** 요청에서 `boundingBox`가, 응답에서 `scanId`·`results[].id`·`reason`이 빠졌고, `degraded`·`matched`·`foodId`·`name`·`koreanName`이 추가됐다. 항목 식별자는 `itemId` → **`idx`** 로 바뀌었고, `matchStatus`(문자열) 대신 **`matched`(불리언)** 를 쓴다. 응답 개수도 요청과 달라질 수 있다.

## 요청

```json
{ "items": [
    { "idx": 10, "rawMenuName": "김치찌개 kimchi jjigae" },
    { "idx": 20, "rawMenuName": "원산지 : 중국" }
]}
```

| 필드 | 타입 | 제약 |
|------|------|------|
| `items` | array | 1~100개 |
| `items[].idx` | int | 필수. **요청 안에서 유일**(중복 시 400). 배열 인덱스를 그대로 써도 되지만 서버는 순서로 해석하지 않는다 |
| `items[].rawMenuName` | string | 필수, blank 불가 |

바운딩 박스는 서버가 쓰지 않으므로 받지 않는다. **언어 파라미터도 받지 않는다** — LLM 호출 비용이 드는 경로라 호출자가 언어를 지정하게 두지 않는다. 표시명은 현재 한국어 고정이며, 회원 언어 설정이 붙으면 그 언어로 지역화된다.

## 응답

```json
{ "success": true,
  "payload": {
    "degraded": false,
    "results": [
      { "idx": 10, "matched": true,  "foodId": 2,    "riskLevel": "DANGER",  "name": "김치찌개", "koreanName": "김치찌개" },
      { "idx": 50, "matched": false, "foodId": 11,   "riskLevel": "UNKNOWN", "name": "우주라면", "koreanName": "우주라면" },
      { "idx": 60, "matched": false, "foodId": null, "riskLevel": "UNKNOWN", "name": null,      "koreanName": null }
    ]
  },
  "message": null }
```

| 필드 | 타입 | 의미 |
|------|------|------|
| `degraded` | boolean | `true`면 정제 서비스 미적용(미구성·실패). 메뉴가 아닌 텍스트가 `results`에 섞여 있을 수 있다 |
| `results[].idx` | int | 요청 `idx`와 짝. **응답 순서가 아니라 이 키로 맞춘다** |
| `results[].matched` | boolean | `true`=조회 가능한 완성 음식과 매칭 / `false`=조사 대기(위험도 판정 불가). **`foodId` 유무로 판단하지 않는다** |
| `results[].foodId` | long? | 음식 PK. `matched=false`여도 조사 대기로 등록된 음식이면 값이 있고, 판정 자체가 불가하면 `null` |
| `results[].riskLevel` | enum | `SAFE`/`CAUTION`/`DANGER`(matched=true) · `UNKNOWN`(matched=false) |
| `results[].name` | string? | 표시명. 현재 한국어 고정(회원 언어 설정 연동 예정). `foodId=null` 이면 null |
| `results[].koreanName` | string? | 언어 무관 한국어 메뉴명. `foodId=null` 이면 null |

`name`·`koreanName` 은 **서버가 아는 음식일 때만** 채워진다. 폴백 중 DB 에도 없는 항목은 서버가 아는 이름이 없으므로 둘 다 `null` 이고, 클라이언트는 자기가 보낸 `rawMenuName` 을 쓴다.

### ⚠️ `results` 개수는 요청보다 적을 수 있다

메뉴가 아닌 항목(원산지·가격·UI 문구·한글 없는 텍스트)은 **결과에서 제외**된다. 클라이언트는 반드시 `idx`로 짝을 맞추고, 응답에 없는 `idx`는 "메뉴 아님"으로 처리한다.

## 상태 조합

| 상황 | `matched` | `foodId` | `riskLevel` | `name` | 응답 포함 |
|------|-----------|----------|-------------|--------|-----------|
| 완성 음식과 매칭 | `true` | 있음 | 산출값 | 한국어 | ✅ |
| 미완성 음식과 매칭 | `false` | 있음 | `UNKNOWN` | 한국어 | ✅ |
| 미등록 → 미완성 등록 | `false` | 있음(신규) | `UNKNOWN` | 한국어 | ✅ |
| 폴백 중 판정 불가 | `false` | `null` | `UNKNOWN` | `null` | ✅ (`degraded=true`) |
| 메뉴가 아님 | — | — | — | — | ❌ 제외 |

`matched` 는 `riskLevel != UNKNOWN` 과 동치다(클라이언트 분기 편의를 위한 중복 필드).

## 실측 예 (Upstage solar-pro)

요청 5항목 → 응답 3항목:

| idx | 원문 | 결과 |
|-----|------|------|
| 10 | `김치찌개 kimchi jjigae` | `matched=true` foodId=2 `DANGER` |
| 20 | `원산지 : 중국` | 제외(비음식) |
| 30 | `된장찌게 8,000` | `matched=true` foodId=1 `DANGER` — 오탈자 교정 |
| 40 | `MacBook Air F9` | 제외(한글 0자, LLM 호출 안 함) |
| 50 | `우주라면` | `matched=false` foodId=11 `UNKNOWN` — 미완성 등록 |

## 오류

| 상태 | 사유 |
|------|------|
| 400 | `items` 비었거나 101개 초과, `idx` 누락·중복, `rawMenuName` blank |
