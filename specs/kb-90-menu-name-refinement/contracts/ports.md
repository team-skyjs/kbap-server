# Contracts: 도메인 Port

application은 아래 port로만 외부와 접한다(헌법 III). 구현은 `:infra:*`, 조립은 `:app:api` `runtimeOnly`.

## KoreanMenuNameNormalizer (`:core:kernel`)

```
fun matchKey(raw: String): String
```

- NFC 정규화 후 `[가-힣]`만 남긴다. 한글이 없으면 `""`.
- 순수·결정적(부수효과 없음).
- 계약 예: `"김치찌개 kimchi jjigae" → "김치찌개"`, `"· 된장찌개 " → "된장찌개"`, `"돼지 국밥" → "돼지국밥"`, `"원산지: 중국" → "원산지중국"`, `"6,500" → ""`.

## ScannedNameInterpreter (`:core:kernel`)

```
fun interpret(texts: List<String>): List<InterpretedName>
```

- **입력**: 정규화 키가 비지 않은(=메뉴 후보) 항목의 원문. 스캔당 **동기 1콜**.
- **출력**: 입력과 **같은 길이·같은 순서**. `InterpretedName = StandardName(korean) | NotFood`.
- **구현 규약**: 실패·타임아웃·파싱 실패는 예외로 던진다. 빈 입력 → 빈 결과(호출 안 함).
- **호출부 규약**: 결과 개수가 요청 개수와 다르면 **신뢰하지 않고** 폴백으로 전환한다(항목 정렬이 깨졌다는 신호).
- Upstage 미구성 시 빈이 없을 수 있음 → application은 **nullable 주입**으로 받아 부재 시 폴백(web 부팅 안전).

## FoodRepository (`:core:food`)

```
fun findById(id: Long): Food?                                  // 상세(사용자) — READY 만
fun findFoodPage(cursor: Long?, size: Int): List<Food>         // 목록(사용자) — READY 만
fun searchFoodPage(keyword, lang, cursor, size): List<Food>    // 검색(사용자) — READY 만
fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> // 스캔 매칭 — 완성 상태 무관
fun createIncomplete(koreanNames: Set<String>): Map<String, Food> // 스캔 miss — 일괄 get-or-create
```

- `findByKoreanMatchKeys`: 스캔당 **1쿼리**(성분 fetch join). 활성(소프트삭제 제외) 음식만. **미완성 음식도 포함**(재등록 방지). 키가 비면 조회하지 않고 빈 맵. 같은 키에 복수 음식이면 **최소 id + 경고 로깅**.
- `createIncomplete`: **스캔당 1회 호출**. 이름 집합을 받아 `korean_name IN (...)` **조회 1회**로 기존 음식을 걸러내고 남은 이름만 `saveAll` 한다. 이미 있는 이름(완성이든 미완성이든)은 그대로 반환하며 미완성으로 덮어쓰지 않는다. 빈 집합이면 쿼리 없이 빈 맵.
  - **INSERT 문은 이름 수만큼 나간다** — `BaseEntity.id` 가 `GenerationType.IDENTITY` 라 Hibernate 가 insert 배치를 끄기 때문이다(생성 PK 를 즉시 받아야 함). 없앤 것은 항목당 SELECT 와 항목당 저장소 왕복이다. 통계로 강제: 이름 5개 → `prepareStatementCount=6`(SELECT 1 + INSERT 5).
  - 소프트 삭제된 동명 음식이 있으면 unique 제약에 막혀 되살아나지 않고, 그 이름은 결과 맵에서 빠진다(경고 로깅). 유스케이스는 `Unmatched(null)`로 흘린다.
- `findById`/`findFoodPage`/`searchFoodPage`: **미완성 음식은 반환하지 않는다**(serving gate).

## 위험도 산출 (`:application:client`)

`Food.overallRisk(avoidedCodes)` 를 유스케이스가 직접 호출한다. `avoidedCodes` 는 `AvoidedSubstanceProvider.avoidedCodes()` 를 `AvoidanceSubstanceCodeRef` 로 옮긴 집합이다.

- **미완성 음식은 도메인이 `UNKNOWN`을 강제**하므로 호출부에 분기가 없다.
- 카탈로그(`AvoidanceSubstanceRepository`) 상태는 보지 않는다 — 목록·검색·상세·스캔 네 경로 동일 규칙(KB-62 `contracts/menu-search-api.md`).
