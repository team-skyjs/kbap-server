# Contract: 스캔 API (X-API-Version 헤더 분기)

## `POST /api/v1/scans?lang=xx`

인증: `Authorization: Bearer {accessToken}` (필수, 게스트 불가)

헤더: `X-API-Version: 2026.08.07` (선택) — 계약 버전(`yyyy.mm.sprint차수`, KB-300 과 동일 정책). **`2026.08.07` 이상이면 v2**, 미전송·이전·형식 오류는 종전 계약(v1). `lang` 은 두 경로 모두 필수(기존 정책 불변).

### Request — v2 (`X-API-Version >= 2026.08.07`)

```json
{ "imagePath": "images/scan/2026/08/1_abc.jpg" }
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `imagePath` | **필수** | 업로드 완료된 메뉴판 사진의 오브젝트 경로(기존 검증 규칙 동일 — 전체 URL 금지·512자) |
| `items` | 무시 | 보내도 idx 매칭에 쓰지 않는다(오류 아님) |

서버가 사진에서 OCR·이름/가격 정제를 수행한다. 응답 `results[].idx` 는 항상 null(클라이언트 박스 매칭 없음).

### Request — v1 (헤더 미전송·이전·형식 오류, 종전 계약 불변)

```json
{ "imagePath": "...", "items": [{ "idx": 0, "rawMenuName": "김치찌개" }] }
```

`items` 1~100개 필수 — 비어 있으면 400 `COMMON-002`(종전과 동일).

### Response (공통 봉투 — 항목 필드 additive 확장)

```json
{
  "success": true,
  "payload": {
    "items": [
      {
        "idx": null,
        "riskLevel": "CAUTION",
        "matched": true,
        "foodId": 12,
        "name": "Kimchi Stew",
        "koreanName": "김치찌개",
        "price": 9000,
        "similarFood": null
      },
      {
        "idx": null,
        "riskLevel": "UNKNOWN",
        "matched": false,
        "foodId": 345,
        "name": "할머니손맛찌개",
        "koreanName": "할머니손맛찌개",
        "price": 12000,
        "similarFood": {
          "foodId": 12,
          "name": "Kimchi Stew",
          "koreanName": "김치찌개",
          "description": "Spicy fermented cabbage stew ...",
          "imageRef": "https://cdn.../foods/12.jpg"
        }
      }
    ],
    "degraded": false
  }
}
```

- **판정 규약**: `matched=true` 정확 매칭 / `matched=false && similarFood != null` **유사 대체(주의 표시)** / 둘 다 아니면 미등록.
- `similarFood.foodId` 는 항상 유효한 등록(READY) 음식 — 기존 음식 상세 조회 API 로 연동 가능.
- `similarFood` 의 필드명·의미는 음식 상세/요약 응답 패턴과 동일 — `name`(요청 언어명)·`koreanName`(지역화명이 곧 한국어면 null)·`description`(번역 부재 ko 폴백)·`imageRef`(공개 URL 로 resolve 된 이미지 참조).
- miss 항목의 `foodId` 는 종전대로 조사 대기 등록된 음식의 id(유사 음식 id 와 별개), `riskLevel` 은 UNKNOWN.
- v1 경로 응답은 종전과 동일 + `similarFood: null`(필드 추가는 하위 호환).

### 오류 (v2 경로)

| 상태 | code | 조건 |
|------|------|------|
| 400 | `COMMON-002` | `imagePath` 누락·형식 오류, `lang` 누락 |
| 400 | `MEMBER-003` | 회원을 찾을 수 없음 |
| 503 | `SCAN-002` | 서버 OCR(비전) 추출 실패 — 구조 깨진 결과만, 빈 결과는 정상 200 |
| 401 | — | 토큰 부재·위조·만료 |

**임베딩·벡터 검색 장애는 오류가 아니다** — 해당 항목의 `similarFood` 만 null 로 응답(부분 성공).

## 계약 검증 방법

| 검증 대상 | 테스트 |
|-----------|--------|
| v2: items 없이 → 200 + 서버 추출 결과 | `ScanControllerTest`(MockMvc + fake 비전) |
| v2: hit 항목 = 등록 음식 정보(v1 과 동등) | `ScanControllerTest` |
| v2: miss 항목 = similarFood(READY foodId·번역 규칙) | `ScanControllerTest`(fake searcher·embedding) |
| v2: 임계 미달·검색 장애·빈 미구성 → similarFood null + 200 | `ScanControllerTest` + `SimilarFoodResolver` 단위 |
| v1: 헤더 없음 + items 누락 → 400 COMMON-002 (종전) | `ScanControllerTest` 기존 + 회귀 |
| 이전 버전·형식 오류 헤더 → v1 동작 | `ScanControllerTest` |
| 서버 OCR 프롬프트 분기(빈 ocrItems) | `OpenAiMenuBoardVisionExtractor` 단위(프롬프트 조립 검증) |
