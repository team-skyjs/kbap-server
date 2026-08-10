# Data Model: 스캔 v2 — 서버 OCR 파이프라인과 유사 음식 대체 응답

**MySQL 스키마·Flyway 변경 없음.** 신규 영속 엔티티 없음. 변경은 seam·요청/응답 타입·서비스 조합이다.

## 신규: `SimilarFoodSearcher` (`com.kbap.api.scan` — 테스트 대역용 인터페이스)

```kotlin
fun interface SimilarFoodSearcher {
    fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument>
}

data class SimilarFoodDocument(
    val foodId: Long,
    val score: Double,
)
```

- DocumentDB 는 영속 접근 취급(research R3 개정 2) — `common.port` seam 이 아니라 api 기능 패키지 소유. 인터페이스는 DocumentDB 를 로컬 재현할 수 없어 fake 주입이 필요한 테스트 사정으로만 존재한다.
- 구현 `DocumentDbSimilarFoodSearcher` + MongoClient 조립(`@ConditionalOnProperty("kbap.vector.enabled")`)도 같은 패키지.
- 반환은 검색 점수 내림차순. 메타데이터(이름·설명·사진)는 반환하지 않는다 — 응답 데이터는 foodId 로 MySQL 재조회(research R5).
- 예외는 구현이 던지고 호출부(`SimilarFoodResolver`)가 잡아 폴백한다.

## 신규: `SimilarFoodResolver` (api — `com.kbap.api.scan`)

miss 항목들의 유사 폴백 유스케이스 조합. 의존이 전부 있어야 동작한다:

| 의존 | 출처 | 부재 시 |
|------|------|---------|
| `TextEmbeddingClient?` | `:infra:llm` (`kbap.llm.embedding.enabled`) | no-op (유사 대체 없음) |
| `SimilarFoodSearcher?` | `com.kbap.api.scan` (`kbap.vector.enabled`) | no-op |
| `FoodService` | `common.domain.food` | — (항상 존재) |

동작: miss 정제명들 → `embed(names)`(배치 1회) → 이름별 `search(embedding, limit=1)` → `score >= threshold` 인 최유사 foodId 수집 → `FoodService` 로 일괄 재조회 → **READY 음식만** 유사 후보로 채택. 임베딩·검색 예외는 warn 로그 후 빈 결과(부분 성공 — research R8).

## 변경: `ScanRequest` (api)

| 필드 | 현행 | 변경 후 |
|------|------|---------|
| `imagePath` | 필수 | 필수 (불변) |
| `items` | `@NotEmpty`(1~100) | **nullable 완화**(`List<...>? = null` — 키 누락 역직렬화 허용) — v1 경로(헤더 없음·이전)는 컨트롤러 분기에서 누락·빈 목록을 `BusinessException(INVALID_REQUEST)`(종전 400 `COMMON-002` 동일 코드)로 거절, v2 경로는 무시(보내도 idx 매칭에 쓰지 않음) |

## 변경: `ScanResult` / `ScanResponse` (api — additive)

`ItemRiskResult` 에 추가:

```kotlin
val similarFood: SimilarFood?   // null = 유사 대체 아님

// 필드명·의미는 FoodDetailResponse/FoodSummaryResponse 패턴을 그대로 따른다.
data class SimilarFood(
    val foodId: Long,
    val name: String,         // 요청 언어 음식명 (displayName(lang) — 기존 언어 정책)
    val koreanName: String?,  // 언어 무관 한국어명 — 지역화명이 곧 한국어면 null (FoodDetailResponse 동일 규약)
    val description: String,  // 요청 언어 설명, 번역 부재 시 ko 폴백 (non-null — READY 음식 전제)
    val imageRef: String?,    // 공개 URL 로 resolve 된 대표 이미지 참조 (기존 food 응답과 동일 필드명·의미)
)
```

판정 규약(클라이언트 계약):

| matched | similarFood | 의미 |
|---------|-------------|------|
| true | null | 정확 매칭 — 등록 음식 정보 |
| false | not null | **유사 대체** — 주의 표시 대상, foodId 로 상세 조회 가능 |
| false | null | 미등록(미조사) — 기존과 동일 |

v1 경로는 `similarFood` 항상 null(필드 추가는 하위 호환).

## 상태·부수 동작 (변경 없음 — 재사용)

| 동작 | v1 | v2 |
|------|----|----|
| miss 이름 조사 대기 등록(`createIncomplete`) | 수행 | 수행 (유사 대체와 별개) |
| scan_history 기록(가격 보존·실패 포함) | 수행 | 수행 (`matchedIdx` 만 null) |
| 스캔 카운트 증가 | 수행 | 수행 |
| 위험도 판정(fail-closed, 미조사=UNKNOWN) | 수행 | 수행 — 유사 대체 항목의 riskLevel 은 원 항목 기준 UNKNOWN 유지(유사 음식의 위험도를 원 메뉴의 것처럼 보이게 하지 않는다) |

## 설정 (신규 프로퍼티)

```yaml
kbap:
  vector:
    enabled: true            # false 면 어댑터 빈 미생성 (local 기본)
    uri: mongodb://...       # DocumentDB 연결 문자열 (TLS·CA 포함)
    database: kbap
    collection: foods
    similarity-threshold: 0.0   # 임계 — dev 실데이터로 튜닝 (0.0 = 항상 채택에서 시작)
  llm:
    embedding:
      enabled: true          # dev/prod 프로필 (local 은 미활성)
```
