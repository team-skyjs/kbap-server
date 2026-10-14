# Contract: POST /api/scans — 유사 음식 대체 (X-API-Version 2.0 / 3.0)

경로·메서드·요청 계약(헤더 `X-Scan-Ticket`·`lang`·`currency`·본문 `imagePath`)은 2.0 과 **동일**하다. 달라지는 것은 응답 항목의 두 필드 값이다.

## 지원 버전

| `X-API-Version` | 핸들러 | 대체 수행 | `substituted` / `originalScannedName` |
|---|---|---|---|
| 1.0 (또는 1.x) | `ScanController`(무버전) | 안 함 | 필드 없음(스키마 무변경) |
| 2.0 | `ScanV2Controller`(`2.0+`) | 함 | **null / null** |
| 3.0 | `ScanV2Controller`(`2.0+`, 같은 핸들러) | 함 | **true·false / 메뉴명·null** |
| 그 외(2.5·4.0 등) | — | — | 400 COMMON-002 (지원 버전 목록: 1.0·1.1·1.2·2.0·3.0) |

## 응답 예시 — 3.0

사진에 "김치찌게"(오타, 신규), "비빔밥"(정상 매칭), "낙지탕탕이"(신규, 유사 없음)가 있고 벡터 색인에 READY "김치찌개"가 0.83 으로 있을 때.

```json
{
  "success": true,
  "payload": {
    "degraded": false,
    "currency": { "code": "USD", "krwPerUnit": 1410.0726 },
    "results": [
      {
        "matched": true,
        "foodId": 7,
        "riskLevel": "CAUTION",
        "name": "Kimchi Stew",
        "koreanName": "김치찌개",
        "price": 9000,
        "imageRef": "https://cdn.example.com/images/webp/kimchi-stew.webp",
        "avoidances": [{ "code": "SHRIMP", "name": "Shrimp", "overlapped": true, "riskLevel": "CAUTION" }],
        "substituted": true,
        "originalScannedName": "김치찌게"
      },
      {
        "matched": true,
        "foodId": 12,
        "riskLevel": "SAFE",
        "name": "Bibimbap",
        "koreanName": "비빔밥",
        "price": 10000,
        "imageRef": "https://cdn.example.com/images/webp/bibimbap.webp",
        "avoidances": [{ "code": "SHRIMP", "name": "Shrimp", "overlapped": false, "riskLevel": null }],
        "substituted": false,
        "originalScannedName": null
      },
      {
        "matched": false,
        "foodId": 9813,
        "riskLevel": "UNKNOWN",
        "name": "낙지탕탕이",
        "koreanName": "낙지탕탕이",
        "price": 15000,
        "imageRef": "https://cdn.example.com/images/default-food.webp",
        "avoidances": [],
        "substituted": false,
        "originalScannedName": null
      }
    ]
  }
}
```

## 응답 예시 — 2.0 (같은 요청)

항목 내용은 3.0 과 동일(첫 항목은 대체돼 `matched=true`·foodId 7). 두 필드만 null.

```json
{ "matched": true, "foodId": 7, "riskLevel": "CAUTION", "name": "Kimchi Stew", "koreanName": "김치찌개",
  "price": 9000, "imageRef": "…/kimchi-stew.webp", "avoidances": [ … ],
  "substituted": null, "originalScannedName": null }
```

## 응답 예시 — 1.0

현재와 바이트 동일. `substituted`·`originalScannedName` 키 없음, 첫 항목은 대체 없이 `matched=false`·신규 음식 id·UNKNOWN.

## 대체 불가 사유 (전부 "준비중" 응답, 3.0 은 `substituted=false`)

| 사유 | 판정 지점 |
|------|-----------|
| 최상위 1건 스코어 < 0.6(코드 상수) | `SimilarFoodService` — `takeIf { score >= SIMILARITY_THRESHOLD }` |
| 최상위 1건이 READY 아님(PENDING_IMAGE·PENDING_REVIEW·FAILED) | `FoodService.getReadyFoodsByIds` 필터 |
| 최상위 1건이 소프트삭제 | `@SQLRestriction(status='ACTIVE')` 로 재조회 누락 |
| 임베딩·벡터 빈 없음(`EMBEDDING_ENABLED`/`VECTOR_ENABLED` false) | nullable 주입 → 빈 맵 |
| 임베딩·벡터 호출 예외·타임아웃 | 전체 catch → warn 로그 + 빈 맵 (스캔은 200) |
| 메뉴가 이미 DB 에 있음(READY 든 미완성이든) | 조회 대상 아님 — `registered` 만 조회 |

차순위 탐색 없음(limit 1).

## 부수 효과

- `scan_history.food_id`: 대체 항목은 대체 음식 id(2.0·3.0 공통). 최근 스캔 목록에 대체 음식이 나타난다.
- `food`: 신규 메뉴는 기존대로 FAILED 로 upsert — 대체와 무관하게 콘텐츠 파이프라인을 탄다.

## 설정

벡터 검색은 기존 `kbap.vector.enabled`(`VECTOR_ENABLED`)·버킷 설정 그대로. 임계값은 설정이 아니라 코드 상수 0.6 이다. 임베딩은 기존 `kbap.llm.embedding.enabled`(`EMBEDDING_ENABLED`) + `OPENAI_API_KEY`. 둘 중 하나라도 꺼져 있으면 대체 단계는 조용히 생략된다.
