# Data Model: 메뉴판 스캔 신규 메뉴의 유사 음식 대체 응답

DB 스키마·Flyway·엔티티 변경 **없음**. 변경은 api 기능 패키지의 결과 타입·응답 DTO 와 설정 1건이다.

## 변경 타입

### `ScanResult.ItemRiskResult` (api 내부 결과, `com.kbap.api.scan`)

| 필드 | 타입 | 추가/기존 | 의미 |
|------|------|-----------|------|
| `substituted` | `Boolean` | **추가** | 이 항목이 유사 완성 음식으로 대체됐는가. true 면 `matched=true` 이고 `foodId` 는 대체 음식 |
| `originalScannedName` | `String?` | **추가** | 대체 전 비전이 정제한 표준 한국어 메뉴명(= 임베딩 입력). 비대체면 null |
| 나머지(`idx`·`riskLevel`·`matched`·`foodId`·`name`·`koreanName`·`price`·`imageRef`·`avoidances`) | | 기존 | 대체 항목은 대체 음식 기준으로 계산(정상 매칭과 동일 식) |

불변: `substituted=true ⇒ matched=true ∧ originalScannedName != null`. `substituted=false ⇒ originalScannedName == null`.

### `ScanV2Response.ItemRiskResponse` (2.0+ 응답 DTO)

| 필드 | 타입 | 3.0 요청 | 2.0 요청 |
|------|------|----------|----------|
| `substituted` | `Boolean?` | true/false | **null** |
| `originalScannedName` | `String?` | 대체면 메뉴명, 아니면 null | **null** |

`from(result, currency, krwPerUnit, exposeSubstitution: Boolean)` — `exposeSubstitution` 은 컨트롤러가 `ApiVersions.isAtLeast(apiVersion, "3.0")` 로 계산.

### `ScanResponse` (1.0 응답 DTO)

변경 없음. `from(result)` 는 새 필드를 읽지 않는다.

### `ApiVersions`

| 상수 | 값 | 용도 |
|------|----|------|
| `SCAN_SUBSTITUTION` | `"3.0"` | 스캔 2.0+ 응답에서 대체 필드 값을 노출하는 최소 버전. `WebConfig.addSupportedVersions` 에 등록 |

### 임계값

| 위치 | 값 | 비고 |
|------|----|------|
| `SimilarFoodService.SIMILARITY_THRESHOLD`(`private const val`) | `0.6` | 설정·환경변수 없음. 조정은 코드 변경 |

## 대체 판정 (항목 1건, 스캔 2.0+ 경로)

```text
menu(koreanName) ──matchKey──▶ foodsByMatchKey[key]
   ├─ known & READY            → matched=true,  substituted=false            (정상 매칭, 벡터 조회 없음)
   ├─ known & !READY           → matched=false, substituted=false            (기존 미완성, 벡터 조회 없음)
   └─ registered(이번 스캔 신규) → similarByName[koreanName] ?
        ├─ 있음(score ≥ threshold ∧ READY 재조회 성공) → food=similar, matched=true, substituted=true, originalScannedName=koreanName
        └─ 없음(미달·비READY·삭제·seam 없음·호출 실패)  → matched=false, substituted=false   (현재 준비중 응답)
```

`similarByName` 은 `SimilarFoodService.resolveSimilarFoods(registeredDisplayNames)` 결과(`Map<String, Food>`). 스캔 1.0 경로(`substituteSimilar=false`)는 이 맵이 항상 빈 맵이다.

## 영속 영향

| 테이블 | 변화 |
|--------|------|
| `food` | 없음 — 신규 메뉴는 기존대로 FAILED 상태로 upsert 된다(대체와 무관) |
| `scan_history` | 스키마 없음. 대체 항목의 `food_id` 값이 신규 미완성 음식 id 대신 **대체 음식 id** 가 된다(`recordHistory` 가 `items[i].foodId` 사용) |
