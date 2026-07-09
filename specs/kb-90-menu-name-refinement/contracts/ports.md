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
- `createIncomplete`: **스캔당 1회 호출**, **문장 2개**(이름 개수 무관). 다중행 `INSERT ... ON DUPLICATE KEY UPDATE id = id` 로 upsert 하고 `korean_name IN (...)` fetch join 으로 다시 읽는다. 빈 집합이면 쿼리 없이 빈 맵. 통계로 강제: 이름 5개 → `prepareStatementCount=2`, `entityInsertCount=0`.
  - **`saveAll` 이 아닌 이유(정합성)**: `food` 에는 `uq_food_korean_name` UNIQUE 가 있다. 두 사용자가 같은 신규 메뉴를 동시에 스캔하면 JPA insert 는 **InnoDB 데드락**(`CannotAcquireLockException` — `DataIntegrityViolationException` 이 아니라 catch 도 안 걸린다)을 내고, 소프트 삭제된 동명 행이 있으면 `DataIntegrityViolationException` 이 세션을 오염시켜 **같은 배치의 멀쩡한 INSERT 까지 롤백**된다. 둘 다 스캔 500. upsert 는 충돌을 예외가 아니라 no-op 으로 만들어 둘 다 없앤다. (회귀 테스트: 경합 2스레드 · 유령 행)
  - `INSERT IGNORE` 는 쓰지 않는다 — 중복뿐 아니라 길이 초과·NULL·FK 위반까지 조용히 삼켜 `food` 를 오염시킨다. `ON DUPLICATE KEY UPDATE` 는 **유니크 충돌만** 무시한다.
  - 소프트 삭제된 동명 음식은 유니크 키를 점유하므로 upsert 가 no-op 이 되고, 후속 조회(`@SQLRestriction`)에서도 안 잡혀 결과 맵에서 빠진다(경고 로깅). 유스케이스는 `Unmatched(null)` → `UNKNOWN` 안전측으로 흘린다. 삭제된 음식을 스캔이 되살리지 않는다.
  - **남은 상한**: 단일 문장이라 데드락 창이 매우 좁지만 0은 아니다. 문제가 되면 데드락 재시도를 얹는다.
  - `korean_name` 은 `VARCHAR(255)` 이고 값이 LLM 출력이므로 **길이 가드가 두 겹**이다 — `ScannedNameParser` 가 255자 초과 표준명을 `NotFood` 로 떨구고, `Food.incomplete()` 가 최후 방어선으로 거절한다(`KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH` 단일 출처).
- `findById`/`findFoodPage`/`searchFoodPage`: **미완성 음식은 반환하지 않는다**(serving gate).

## 위험도 산출 (`:application:client`)

`Food.overallRisk(avoidedCodes)` 를 유스케이스가 직접 호출한다. `avoidedCodes` 는 `AvoidedSubstanceProvider.avoidedCodes()` 를 `AvoidanceSubstanceCodeRef` 로 옮긴 집합이다.

- **미완성 음식은 도메인이 `UNKNOWN`을 강제**하므로 호출부에 분기가 없다.
- 카탈로그(`AvoidanceSubstanceRepository`) 상태는 보지 않는다 — 목록·검색·상세·스캔 네 경로 동일 규칙(KB-62 `contracts/menu-search-api.md`).
